/**
 *  Fronius Solar Inverter Driver
 *
 *	Copyright 2024 Derek Osborn
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  May 2024 - Modified by Derek Osborn 
 *	v2.0.0 - Updated to convert Watt hours to Kilowatt hours, Added Dashboard tile, and published to HPM for easier access
 *  
 *  Source:
 *  https://github.com/dJOS1475/Fronius_Inverter_Driver_Hubitat
 *
 *  Forked from:
 *  https://github.com/SebastienViel/FroniusInverter/tree/main
 *
 */
 
import groovy.json.JsonSlurper
import java.math.BigDecimal

def version() {
    return "2.0.0"
}

preferences {
	input name: "about", type: "paragraph", element: "paragraph", title: "Fronius Solar Inverter Driver", description: "v.${version()}"
	input("inverterNumber", "number", title: "Inverter Number", description: "The Inverter Number", required: true, displayDuringSetup: true)
	input("destIp", "text", title: "IP", description: "The device IP", required: true, displayDuringSetup: true)
	input("destPort", "number", title: "Port", description: "The port you wish to connect", required: true, displayDuringSetup: true)
	input("logDebugEnable", "bool", title: "Enable Debug logging", required: true, defaultValue: false)
}

metadata {
	definition (
		name: "Fronius Solar Inverter",
		namespace: "dJOS",
		author: "Derek Osborn",
		importUrl:"https://raw.githubusercontent.com/dJOS1475/Fronius_Inverter_Driver_Hubitat/main/Fronius_Solar_Inverter.groovy"
	) {
	capability "Polling"
	capability "PowerMeter"
	capability "EnergyMeter"
	capability "Actuator"
	capability "Refresh"
	capability "Sensor"
	capability "VoltageMeasurement"
		
	attribute "errorCode", "number"
	attribute "pGrid", "number"
	attribute "pLoad", "number"
	attribute "TotalEnergy", "number"
	attribute "YearValue", "number"
	attribute "DayValue", "number"
    attribute 'healthStatus', 'enum', ['unknown', 'offline', 'online']

    attribute "energyTodayKWh", "number"
    attribute "energyTotalKWh", "number"
    attribute "energyYearKWh", "number"
    attribute "htmlEnergy", "string"
	}
}

def initialize() {
	if(logDebugEnable) log.info "Fronius Inverter ${textVersion()} ${textCopyright()}"
	sendEvent(name: "power", value: 0	)
	sendEvent(name: "YearValue", value: 0 )
	sendEvent(name: "energy", value: 0 )
	sendEvent(name: "pGrid", value: 0 )
	sendEvent(name: "pLoad", value: 0 )
	sendEvent(name: "TotalValue", value: 0 )
	state.parseCallCounter=0
	// updated()
	poll()
}

// parse events into attributes
def parse(String description) {	
	def msg = parseLanMessage(description)
	def slurper = new JsonSlurper()
	def result = slurper.parseText(msg.body)
	def errorCode = result?.Body?.Data?."$inverterNumber"?.ErrorCode
	if(errorCode != null) {
		sendEvent(name: "errorCode", value: errorCode )
	} else {
		sendEvent(name: "errorCode", value: 0 )
		if(logDebugEnable) log.debug "Received data from inverter: ${result.Body.Data.Site}"

		int yearValue = result.Body.Data.Site.E_Year
		int dayValue = result.Body.Data.Site.E_Day
		int totalValue = result.Body.Data.Site.E_Total
		def pGridValue = result.Body.Data.Site.P_Grid
		int pGrid = pGridValue != null ? pGridValue.toInteger() : 0
		def pLoadValue = result.Body.Data.Site.P_Load
		int pLoad = pLoadValue != null ? pLoadValue.toInteger() : 0
			pLoad = -pLoad
		def pPV = result.Body.Data.Site.P_PV
		int power = pPV != null ? pPV.toInteger() : 0

		sendEvent(name: "power", value: power, unit: "W" )
		sendEvent(name: "energy", value: dayValue, unit: "Wh")
		sendEvent(name: "eYear", value: yearValue / 1000, unit: "kWH")
		def value = new BigDecimal(totalValue / 1000).setScale(3, BigDecimal.ROUND_HALF_UP)
		sendEvent(name: "TotalEnergy", value: value, unit: "kWH")
		sendEvent(name: "pGrid", value: pGrid, unit: "W")
		sendEvent(name: "pLoad", value: pLoad, unit: "W")

        //Convert Wh values to kWh Values
        def energyValuesWh = [
            energyTodayWh: result.Body.Data.Site.E_Day,
            energyTotalWh: result.Body.Data.Site.E_Total,
            energyYearWh: result.Body.Data.Site.E_Year
        ]
        
        energyValuesWh.each { name, whValue ->
            def kwhValue = Math.round((whValue / 1000.0) * 100) / 100.0
            sendEvent(name: name, value: whValue)
            sendEvent(name: name.replace("Wh", "KWh"), value: kwhValue)
            log.debug "${whValue} Wh is equal to ${kwhValue} kWh"
        }
        //Update HTML Tile
        htmlTile()

		//Keep track of when the last update came in
		if (state.parseCallCounter>0) {
			updated()    // reset the poll timer back to 1 min if we got an answer
        		sendEvent(name: "healthStatus", value: "online")
		}
		state.parseCallCounter=0

		// Log the received data for debugging
		if(logDebugEnable) log.debug "Received data from inverter: ${result}"
	}
}

def htmlTile() {	
	htmlEnergy ="<div style='line-height:1.0; font-size:0.75em;'><br>Solar Energy Produced:<br></div>"
    htmlEnergy +="<div style='line-height:50%;'><br></div>"
    htmlEnergy +="<div style='line-height:1.0; font-size:0.75em;'><br>Today: ${device.currentValue('energyTodayKWh')} kWh<br></div>"
    htmlEnergy +="<div style='line-height:50%;'><br></div>"
    htmlEnergy +="<div style='line-height:1.0; font-size:0.75em;'><br>This Year: ${device.currentValue('energyYearKWh')} kWh<br></div>"
    htmlEnergy +="<div style='line-height:1.0; font-size:0.75em;'><br>Lifetime: ${device.currentValue('energyTotalKWh')} kWh<br></div>"
	sendEvent(name: "htmlEnergy", value: htmlEnergy)
	if(txtEnable == true){log.debug "htmlEnergy contains ${htmlEnergy}"}		
	if(txtEnable == true){log.debug "${htmlEnergy.length()}"}			
	}

// handle commands
def poll() {
	if (state.parseCallCounter==null) {
		state.parseCallCounter=1
	} else {
		state.parseCallCounter++
	}
	if (state.parseCallCounter==3) {
		// Not getting Fronius's replies so could be sleeping.  Set the timer with 30 min interval
		sendEvent(name: "healthStatus", value: "offline")
		unschedule(refresh)
		schedule('0 */30 * ? * *', refresh)
	}
	callInvertor()
}


def callInvertor() {
	try {
		def hosthex = convertIPtoHex(destIp)
		def porthex = convertPortToHex(destPort)
		device.deviceNetworkId = "$hosthex:$porthex" 

		sendHubCommand(new hubitat.device.HubAction(
			'method': 'GET',
			'path': "/solar_api/v1/GetInverterInfo.cgi?Scope=System",
			'headers': [ HOST: "$destIp:$destPort" ]
		))

		//This is the call to get data	    
		def hubAction = new hubitat.device.HubAction(
			'method': 'GET',
			'path': "/solar_api/v1/GetPowerFlowRealtimeData.fcgi",
			'headers': [ HOST: "$destIp:$destPort" ]
			) 
		hubAction
	}
	catch (Exception e) {
		if(logDebugEnable) log.debug "Hit Exception $e on $hubAction"
	}
}

private def textVersion() {
	def text = "${version.length()}"
}

private def textCopyright() {
	def text = "Copyright © 2024 Derek Osborn"
}

private String convertIPtoHex(ipAddress) { 
	String hex = ipAddress.tokenize( '.' ).collect {  String.format( '%02X', it.toInteger() ) }.join()
	return hex
}

private String convertPortToHex(port) {
	String hexport = port.toString().format( '%04X', port.toInteger() )
    	return hexport
}

def refresh(){
	poll()
}

def updated(){
	unschedule(refresh)
	schedule('0 */1 * ? * *', refresh)
	state.parseCallCounter=0
}
