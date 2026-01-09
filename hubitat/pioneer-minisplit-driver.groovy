/**
 * Pioneer WYT Mini-Split Driver for Hubitat
 *
 * Controls Pioneer WYT (Diamante) mini-split heat pumps via local bridge API.
 * Requires the FastAPI bridge service running on the local network.
 *
 * Author: signal15
 * License: MIT
 * Repository: https://github.com/signal15/tuya-minisplit-hubitat
 */

metadata {
    definition(
        name: "Pioneer Mini-Split",
        namespace: "signal15",
        author: "signal15",
        importUrl: "https://raw.githubusercontent.com/signal15/tuya-minisplit-hubitat/main/hubitat/pioneer-minisplit-driver.groovy"
    ) {
        capability "Thermostat"
        capability "ThermostatMode"
        capability "ThermostatFanMode"
        capability "ThermostatSetpoint"
        capability "ThermostatCoolingSetpoint"
        capability "ThermostatHeatingSetpoint"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Switch"
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"

        // Custom attributes
        attribute "verticalSwing", "string"
        attribute "horizontalSwing", "string"
        attribute "filterStatus", "string"
        attribute "bridgeStatus", "string"

        // Custom commands
        command "setVerticalSwing", [[name: "position", type: "ENUM", constraints: ["off", "full", "upper", "lower"]]]
        command "setHorizontalSwing", [[name: "position", type: "ENUM", constraints: ["off", "full", "left", "center", "right"]]]
        command "dry"
        command "fanOnly"
    }

    preferences {
        input name: "bridgeUrl", type: "text", title: "Bridge URL", description: "e.g., http://192.168.1.100:8000", required: true
        input name: "bridgeToken", type: "password", title: "Bridge Token", description: "Bearer token for authentication", required: true
        input name: "tempUnit", type: "enum", title: "Temperature Unit", options: ["F", "C"], defaultValue: "F"
        input name: "pollingInterval", type: "enum", title: "Polling Interval", options: ["30", "60", "120", "300"], defaultValue: "60"
        input name: "enableSwingControl", type: "bool", title: "Enable Swing Controls", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable Debug Logging", defaultValue: false
    }
}

// Standard thermostat modes
def getSupportedThermostatModes() {
    return ["off", "heat", "cool", "auto", "fan_only", "dry"]
}

def getSupportedThermostatFanModes() {
    return ["auto", "low", "medium", "high"]
}

// Lifecycle methods
def installed() {
    log.info "Pioneer Mini-Split driver installed"
    initialize()
}

def updated() {
    log.info "Pioneer Mini-Split driver updated"
    unschedule()
    initialize()
}

def initialize() {
    log.info "Initializing Pioneer Mini-Split driver"

    // Set initial states
    sendEvent(name: "supportedThermostatModes", value: getSupportedThermostatModes())
    sendEvent(name: "supportedThermostatFanModes", value: getSupportedThermostatFanModes())

    // Schedule polling
    def interval = (settings?.pollingInterval ?: "60").toInteger()
    schedule("0/${interval} * * * * ?", refresh)

    // Initial refresh
    runIn(2, refresh)
}

// Refresh / polling
def refresh() {
    if (logEnable) log.debug "Refreshing status..."

    def params = [
        uri: "${settings.bridgeUrl}/status?refresh=true",
        headers: [
            "Authorization": "Bearer ${settings.bridgeToken}",
            "Content-Type": "application/json"
        ],
        timeout: 10
    ]

    try {
        httpGet(params) { response ->
            if (response.status == 200) {
                def data = response.data
                if (logEnable) log.debug "Status response: ${data}"

                if (data.online) {
                    sendEvent(name: "bridgeStatus", value: "online")

                    // Power / switch state
                    if (data.power != null) {
                        sendEvent(name: "switch", value: data.power ? "on" : "off")
                    }

                    // Temperature
                    if (data.current_temp != null) {
                        sendEvent(name: "temperature", value: data.current_temp, unit: settings?.tempUnit ?: "F")
                    }

                    // Target temperature / setpoints
                    if (data.target_temp != null) {
                        def setpoint = data.target_temp
                        sendEvent(name: "thermostatSetpoint", value: setpoint, unit: settings?.tempUnit ?: "F")

                        // Set appropriate setpoint based on mode
                        def mode = data.mode ?: device.currentValue("thermostatMode")
                        if (mode == "cool") {
                            sendEvent(name: "coolingSetpoint", value: setpoint, unit: settings?.tempUnit ?: "F")
                        } else if (mode == "heat") {
                            sendEvent(name: "heatingSetpoint", value: setpoint, unit: settings?.tempUnit ?: "F")
                        }
                    }

                    // Mode
                    if (data.mode != null) {
                        def mode = data.mode
                        // Map to Hubitat conventions
                        if (mode == "fan_only") mode = "fan_only"
                        sendEvent(name: "thermostatMode", value: mode)

                        // Set operating state based on mode and power
                        def opState = "idle"
                        if (data.power) {
                            switch (mode) {
                                case "cool": opState = "cooling"; break
                                case "heat": opState = "heating"; break
                                case "fan_only": opState = "fan only"; break
                                case "dry": opState = "idle"; break  // No standard state for dry
                                default: opState = "idle"
                            }
                        }
                        sendEvent(name: "thermostatOperatingState", value: opState)
                    }

                    // Fan
                    if (data.fan != null) {
                        sendEvent(name: "thermostatFanMode", value: data.fan)
                    }

                    // Humidity
                    if (data.humidity != null) {
                        sendEvent(name: "humidity", value: data.humidity, unit: "%")
                    }

                    // Swing
                    if (data.vert_swing != null) {
                        sendEvent(name: "verticalSwing", value: data.vert_swing)
                    }
                    if (data.horiz_swing != null) {
                        sendEvent(name: "horizontalSwing", value: data.horiz_swing)
                    }

                    // Filter
                    if (data.filter_dirty != null) {
                        sendEvent(name: "filterStatus", value: data.filter_dirty ? "dirty" : "clean")
                    }

                } else {
                    sendEvent(name: "bridgeStatus", value: "device offline")
                }
            }
        }
    } catch (Exception e) {
        log.error "Refresh failed: ${e.message}"
        sendEvent(name: "bridgeStatus", value: "error: ${e.message}")
    }
}

// Switch capability
def on() {
    if (logEnable) log.debug "Turning on"
    sendCommand("power", true)
}

def off() {
    if (logEnable) log.debug "Turning off"
    sendCommand("power", false)
}

// Thermostat mode
def setThermostatMode(mode) {
    if (logEnable) log.debug "Setting thermostat mode: ${mode}"

    if (mode == "off") {
        off()
        return
    }

    // Ensure device is on
    if (device.currentValue("switch") != "on") {
        on()
        pauseExecution(500)
    }

    def tuyaMode = mode
    switch (mode) {
        case "fan_only": tuyaMode = "wind"; break
        case "dry": tuyaMode = "wet"; break
    }

    sendCommand("mode", tuyaMode)
}

def auto() { setThermostatMode("auto") }
def cool() { setThermostatMode("cool") }
def heat() { setThermostatMode("heat") }
def dry() { setThermostatMode("dry") }
def fanOnly() { setThermostatMode("fan_only") }
def emergencyHeat() { heat() }  // No emergency heat, just use heat

// Temperature setpoints
def setCoolingSetpoint(temp) {
    if (logEnable) log.debug "Setting cooling setpoint: ${temp}"
    sendCommand("target_temp", temp)
    sendEvent(name: "coolingSetpoint", value: temp, unit: settings?.tempUnit ?: "F")
    sendEvent(name: "thermostatSetpoint", value: temp, unit: settings?.tempUnit ?: "F")
}

def setHeatingSetpoint(temp) {
    if (logEnable) log.debug "Setting heating setpoint: ${temp}"
    sendCommand("target_temp", temp)
    sendEvent(name: "heatingSetpoint", value: temp, unit: settings?.tempUnit ?: "F")
    sendEvent(name: "thermostatSetpoint", value: temp, unit: settings?.tempUnit ?: "F")
}

def setThermostatSetpoint(temp) {
    def mode = device.currentValue("thermostatMode")
    if (mode == "cool") {
        setCoolingSetpoint(temp)
    } else if (mode == "heat") {
        setHeatingSetpoint(temp)
    } else {
        sendCommand("target_temp", temp)
        sendEvent(name: "thermostatSetpoint", value: temp, unit: settings?.tempUnit ?: "F")
    }
}

// Fan mode
def setThermostatFanMode(mode) {
    if (logEnable) log.debug "Setting fan mode: ${mode}"
    sendCommand("fan", mode)
}

def fanAuto() { setThermostatFanMode("auto") }
def fanCirculate() { setThermostatFanMode("medium") }
def fanOn() { setThermostatFanMode("high") }

// Swing controls
def setVerticalSwing(position) {
    if (!settings?.enableSwingControl) {
        log.warn "Swing control is disabled in preferences"
        return
    }
    if (logEnable) log.debug "Setting vertical swing: ${position}"
    sendCommand("vert_swing", position)
}

def setHorizontalSwing(position) {
    if (!settings?.enableSwingControl) {
        log.warn "Swing control is disabled in preferences"
        return
    }
    if (logEnable) log.debug "Setting horizontal swing: ${position}"
    sendCommand("horiz_swing", position)
}

// Generic command sender
private def sendCommand(command, value) {
    def params = [
        uri: "${settings.bridgeUrl}/command",
        headers: [
            "Authorization": "Bearer ${settings.bridgeToken}",
            "Content-Type": "application/json"
        ],
        body: [
            command: command,
            value: value
        ],
        timeout: 10
    ]

    try {
        httpPostJson(params) { response ->
            if (response.status == 200) {
                if (logEnable) log.debug "Command response: ${response.data}"

                // Update state from response
                if (response.data?.status) {
                    def status = response.data.status
                    if (status.power != null) {
                        sendEvent(name: "switch", value: status.power ? "on" : "off")
                    }
                    if (status.mode != null) {
                        sendEvent(name: "thermostatMode", value: status.mode)
                    }
                    if (status.target_temp != null) {
                        sendEvent(name: "thermostatSetpoint", value: status.target_temp)
                    }
                    if (status.fan != null) {
                        sendEvent(name: "thermostatFanMode", value: status.fan)
                    }
                }

                return true
            } else {
                log.error "Command failed with status: ${response.status}"
                return false
            }
        }
    } catch (Exception e) {
        log.error "Command failed: ${e.message}"
        sendEvent(name: "bridgeStatus", value: "error: ${e.message}")
        return false
    }
}
