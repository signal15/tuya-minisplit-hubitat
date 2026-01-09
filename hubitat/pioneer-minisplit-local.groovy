/**
 * Pioneer WYT Mini-Split Local Driver for Hubitat
 *
 * Native local control of Pioneer WYT (Diamante) mini-split via Tuya protocol.
 * No bridge required - direct communication from Hubitat to device.
 *
 * Based on ivarho's Tuya Generic Device driver (Apache 2.0 License)
 * Modified for thermostat capabilities by signal15
 *
 * Repository: https://github.com/signal15/tuya-minisplit-hubitat
 */

metadata {
    definition(name: "Pioneer Mini-Split Local", namespace: "signal15", author: "signal15",
               importUrl: "https://raw.githubusercontent.com/signal15/tuya-minisplit-hubitat/main/hubitat/pioneer-minisplit-local.groovy") {
        capability "Actuator"
        capability "Switch"
        capability "Thermostat"
        capability "ThermostatMode"
        capability "ThermostatFanMode"
        capability "ThermostatSetpoint"
        capability "ThermostatCoolingSetpoint"
        capability "ThermostatHeatingSetpoint"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Sensor"
        capability "Refresh"
        capability "PresenceSensor"

        command "status"
        command "Disconnect"
        command "dry"
        command "fanOnly"

        attribute "filterStatus", "string"
        attribute "rawDps", "string"
    }
}

preferences {
    section("Device Configuration") {
        input "ipaddress", "text", title: "Device IP:", required: true,
              description: "Pioneer mini-split IP address (e.g., 10.129.1.97)"
        input "devId", "text", title: "Device ID:", required: true,
              description: "Tuya device ID (from discovery)"
        input "localKey", "text", title: "Local Key:", required: true,
              description: "16-character encryption key from Tuya IoT"
        input "tuyaProtVersion", "enum", title: "Protocol Version:", required: true,
              options: [31: "3.1", 33: "3.3", 34: "3.4"], defaultValue: "33"
    }
    section("Polling & Connection") {
        input name: "poll_interval", type: "enum", title: "Poll Interval:",
              options: [0: "No polling", 30: "30 seconds", 60: "1 minute", 120: "2 minutes"],
              defaultValue: "60"
        input name: "autoReconnect", type: "bool", title: "Auto Reconnect", defaultValue: true
        input name: "heartBeatMethod", type: "bool", title: "Use Heartbeat", defaultValue: false
    }
    section("Logging") {
        input name: "logEnable", type: "bool", title: "Enable Debug Logging", defaultValue: false
    }
}

// ==================== DPID MAPPINGS ====================
@Field static Map DPID = [
    power: "1",
    targetTemp: "2",
    currentTemp: "3",
    mode: "4",
    fan: "5",
    humidity: "18",
    faultCode: "20",
    sleepMode: "105",
    vertSwing: "113",
    horizSwing: "114",
    ecoMode: "119",
    displayBeep: "123",
    filterDirty: "131"
]

@Field static Map MODE_MAP = [
    "cold": "cool",
    "hot": "heat",
    "wet": "dry",
    "wind": "fan_only",
    "auto": "auto"
]

@Field static Map MODE_REVERSE = [
    "cool": "cold",
    "heat": "hot",
    "dry": "wet",
    "fan_only": "wind",
    "auto": "auto",
    "off": "off"
]

@Field static Map FAN_MAP = [
    "auto": "auto",
    "quiet": "low",
    "low": "low",
    "medium-low": "medium",
    "medium": "medium",
    "medium-high": "medium",
    "high": "high",
    "strong": "high"
]

// ==================== LIFECYCLE ====================
def installed() {
    log.info "Pioneer Mini-Split driver installed"
    initialize()
}

def updated() {
    log.info "Pioneer Mini-Split driver updated"
    if (logEnable) runIn(1800, logsOff)

    _updatedTuya()

    // Set up polling
    unschedule(status)
    if (poll_interval && poll_interval.toInteger() > 0) {
        schedule("0/${poll_interval} * * ? * *", status)
    }

    initialize()
    runIn(2, status)
}

def initialize() {
    sendEvent(name: "supportedThermostatModes", value: ["off", "heat", "cool", "auto", "fan_only", "dry"])
    sendEvent(name: "supportedThermostatFanModes", value: ["auto", "low", "medium", "high"])
}

def logsOff() {
    log.warn "Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def refresh() {
    status()
}

// ==================== THERMOSTAT COMMANDS ====================
def on() {
    if (logEnable) log.debug "Turning ON"
    send("set", ["${DPID.power}": true])
}

def off() {
    if (logEnable) log.debug "Turning OFF"
    send("set", ["${DPID.power}": false])
}

def heat() {
    setThermostatMode("heat")
}

def cool() {
    setThermostatMode("cool")
}

def auto() {
    setThermostatMode("auto")
}

def dry() {
    setThermostatMode("dry")
}

def fanOnly() {
    setThermostatMode("fan_only")
}

def emergencyHeat() {
    heat()
}

def setThermostatMode(mode) {
    if (logEnable) log.debug "Setting mode: ${mode}"

    if (mode == "off") {
        off()
        return
    }

    // Make sure device is on
    def currentPower = device.currentValue("switch")
    if (currentPower != "on") {
        send("set", ["${DPID.power}": true])
        pauseExecution(500)
    }

    def tuyaMode = MODE_REVERSE[mode] ?: mode
    send("set", ["${DPID.mode}": tuyaMode])
}

def setCoolingSetpoint(temp) {
    if (logEnable) log.debug "Setting cooling setpoint: ${temp}"
    setTemperature(temp)
}

def setHeatingSetpoint(temp) {
    if (logEnable) log.debug "Setting heating setpoint: ${temp}"
    setTemperature(temp)
}

def setThermostatSetpoint(temp) {
    setTemperature(temp)
}

def setTemperature(temp) {
    // Device expects Fahrenheit × 10
    def tempValue = (temp * 10).toInteger()

    // Clamp to valid range (61-86°F = 610-860)
    tempValue = Math.max(610, Math.min(860, tempValue))

    if (logEnable) log.debug "Setting temperature: ${temp}°F (raw: ${tempValue})"
    send("set", ["${DPID.targetTemp}": tempValue])
}

def setThermostatFanMode(mode) {
    if (logEnable) log.debug "Setting fan mode: ${mode}"

    def tuyaFan = mode
    switch(mode) {
        case "low": tuyaFan = "low"; break
        case "medium": tuyaFan = "medium"; break
        case "high": tuyaFan = "high"; break
        case "on": tuyaFan = "high"; break
        case "circulate": tuyaFan = "medium"; break
        default: tuyaFan = "auto"
    }

    send("set", ["${DPID.fan}": tuyaFan])
}

def fanAuto() { setThermostatFanMode("auto") }
def fanCirculate() { setThermostatFanMode("medium") }
def fanOn() { setThermostatFanMode("high") }

// ==================== STATUS PARSING ====================
def parse(String message) {
    List results = _parseTuya(message)

    results.each { status_object ->
        if (status_object?.dps) {
            parseDps(status_object.dps)
        }
    }
}

def parseDps(Map dps) {
    if (logEnable) log.debug "Parsing DPS: ${dps}"

    sendEvent(name: "rawDps", value: dps.toString())

    // Power
    if (dps[DPID.power] != null) {
        def power = dps[DPID.power]
        sendEvent(name: "switch", value: power ? "on" : "off")

        if (!power) {
            sendEvent(name: "thermostatMode", value: "off")
            sendEvent(name: "thermostatOperatingState", value: "idle")
        }
    }

    // Target temperature (raw value is F × 10)
    if (dps[DPID.targetTemp] != null) {
        def tempF = dps[DPID.targetTemp] / 10.0
        sendEvent(name: "thermostatSetpoint", value: tempF, unit: "°F")

        def mode = device.currentValue("thermostatMode")
        if (mode == "cool") {
            sendEvent(name: "coolingSetpoint", value: tempF, unit: "°F")
        } else if (mode == "heat") {
            sendEvent(name: "heatingSetpoint", value: tempF, unit: "°F")
        }
    }

    // Current temperature (raw value is Celsius)
    if (dps[DPID.currentTemp] != null) {
        def tempC = dps[DPID.currentTemp]
        def tempF = (tempC * 9.0 / 5.0) + 32.0
        sendEvent(name: "temperature", value: tempF.round(1), unit: "°F")
    }

    // Mode
    if (dps[DPID.mode] != null) {
        def tuyaMode = dps[DPID.mode]
        def hubMode = MODE_MAP[tuyaMode] ?: tuyaMode

        def power = device.currentValue("switch")
        if (power == "on") {
            sendEvent(name: "thermostatMode", value: hubMode)

            // Set operating state
            def opState = "idle"
            switch(hubMode) {
                case "cool": opState = "cooling"; break
                case "heat": opState = "heating"; break
                case "fan_only": opState = "fan only"; break
                default: opState = "idle"
            }
            sendEvent(name: "thermostatOperatingState", value: opState)
        }
    }

    // Fan
    if (dps[DPID.fan] != null) {
        def tuyaFan = dps[DPID.fan]
        def hubFan = FAN_MAP[tuyaFan] ?: "auto"
        sendEvent(name: "thermostatFanMode", value: hubFan)
    }

    // Humidity
    if (dps[DPID.humidity] != null) {
        sendEvent(name: "humidity", value: dps[DPID.humidity], unit: "%")
    }

    // Filter status
    if (dps[DPID.filterDirty] != null) {
        sendEvent(name: "filterStatus", value: dps[DPID.filterDirty] ? "dirty" : "clean")
    }
}

def status() {
    send("status", [:])
}

// ==================== TUYA PROTOCOL (from ivarho) ====================

import hubitat.device.HubAction
import hubitat.device.Protocol
import groovy.transform.Field

def socketStatus(String socketMessage) {
    if(logEnable) log.info "Socket status: ${socketMessage}"

    if (socketMessage == "send error: Broken pipe (Write failed)") {
        unschedule(heartbeat)
        socket_close()
    }

    if (socketMessage.contains('disconnect')) {
        unschedule(heartbeat)
        socket_close(settings.autoReconnect == true)

        if (settings.autoReconnect == true || settings.autoReconnect == null) {
            state.HaveSession = get_session(settings.tuyaProtVersion)
            if (state.HaveSession == false) {
                sendEvent(name: "presence", value: "not present")
            }
        }
    }
}

boolean socket_connect() {
    if (logEnable) log.debug "Connecting to ${settings.ipaddress}:6668"

    try {
        interfaces.rawSocket.connect(settings.ipaddress, 6668, byteInterface: true, readDelay: 150)
        return true
    } catch (e) {
        log.error "Connection failed: ${e}"
        return false
    }
}

def socket_write(byte[] message) {
    String msg = hubitat.helper.HexUtils.byteArrayToHexString(message)
    if (logEnable) log.debug "Sending: ${msg}"

    try {
        interfaces.rawSocket.sendMessage(msg)
    } catch (e) {
        log.error "Send error: ${e}"
    }
}

def socket_close(boolean willReconnect=false) {
    if(logEnable) log.debug "Closing socket"

    unschedule(sendTimeout)

    if (!willReconnect) {
        sendEvent(name: "presence", value: "not present")
    }

    state.session_step = "step1"
    state.HaveSession = false
    state.sessionKey = null

    try {
        interfaces.rawSocket.close()
    } catch (e) {
        log.error "Close error: ${e}"
    }
}

@Field static String fCommand = ""
@Field static Map fMessage = [:]

def send(String command, Map message=null) {
    boolean sessionState = state.HaveSession

    if (sessionState == false) {
        if(logEnable) log.debug "Creating new session"
        sessionState = get_session(settings.tuyaProtVersion)
    }

    if (sessionState) {
        socket_write(generate_payload(command, message))
    }

    fCommand = command
    fMessage = message

    state.HaveSession = sessionState
    runInMillis(1000, sendTimeout)
}

def sendAll() {
    if (fCommand != "") {
        send(fCommand, fMessage)
    }
}

def sendTimeout() {
    if (state.retry > 0) {
        if (logEnable) log.warn "Retrying..."
        state.retry = state.retry - 1
        sendAll()
    } else {
        log.error "No response after 5 retries"
        socket_close()
    }
}

Short getNewMessageSequence() {
    if (state.Msgseq == null) state.Msgseq = 0
    state.Msgseq = state.Msgseq + 1
    return state.Msgseq
}

byte[] getRealLocalKey() {
    return localKey.replaceAll('&lt;', '<').getBytes("UTF-8")
}

def _updatedTuya() {
    state.statePayload = [:]
    state.HaveSession = false
    state.session_step = "step1"
    state.retry = 5
    state.Msgseq = 1
}

def Disconnect() {
    unschedule(heartbeat)
    socket_close()
}

def heartbeat() {
    send("hb")
    runIn(30, socketStatus, [data: "disconnect: pipe closed"])
}

@Field static Map frameTypes = [
    3: "KEY_START", 4: "KEY_RESP", 5: "KEY_FINAL",
    7: "CONTROL", 8: "STATUS_RESP", 9: "HEART_BEAT",
    10: "DP_QUERY", 13: "CONTROL_NEW", 16: "DP_QUERY_NEW"
]

def getFrameTypeId(String name) {
    return frameTypes.find{it.value == name}.key
}

@Field static Map frameChecksumSize = ["31": 4, "33": 4, "34": 32]

List _parseTuya(String message) {
    if(logEnable) log.debug "Parsing: ${message}"

    unschedule(sendTimeout)
    state.retry = 5

    String start = "000055AA"
    List startIndexes = []

    int index = 0
    int location = 0
    int loopGuard = 100

    while (index < message.size() && loopGuard > 0) {
        index = message.indexOf(start, location)
        location = index + 1

        if (index != -1) {
            startIndexes.add(index/2)
        } else {
            break
        }
        loopGuard--
    }

    byte[] incomingData = hubitat.helper.HexUtils.hexStringToByteArray(message)
    List results = []

    startIndexes.each {
        Map result = decodeIncomingFrame(incomingData as byte[], it as Integer)
        if (result != null && result != [:]) {
            results.add(result)
        }
    }

    return results
}

Map decodeIncomingFrame(byte[] incomingData, Integer sofIndex=0, byte[] testKey=null, Closure callback=null) {
    def frameType = Byte.toUnsignedInt(incomingData[sofIndex + 11])
    Integer frameLength = Byte.toUnsignedInt(incomingData[sofIndex + 15])

    if (!frameTypes.containsKey(frameType)) {
        log.warn "Unknown frame type: ${frameType}"
        return [:]
    }

    byte[] useKey = getRealLocalKey()
    if (testKey != null) {
        useKey = testKey
    } else if (state.sessionKey != null) {
        useKey = state.sessionKey
    }

    Integer checksumSize = frameChecksumSize[settings.tuyaProtVersion]
    Integer payloadStart = 20
    Integer payloadLength = 16

    switch (frameTypes[frameType]) {
        case "KEY_RESP":
            payloadStart = 20
            payloadLength = frameLength - checksumSize - 8
            useKey = getRealLocalKey()
            unschedule(get_session_timeout)
            break
        case "CONTROL":
        case "CONTROL_NEW":
            return [:]
        case "STATUS_RESP":
            fCommand = ""
            if (settings.tuyaProtVersion == "31") {
                payloadStart = 39
                payloadLength = frameLength - checksumSize - 27
            } else if (settings.tuyaProtVersion == "33") {
                payloadStart = 35
                payloadLength = frameLength - checksumSize - 23
            } else if (settings.tuyaProtVersion == "34") {
                payloadStart = 20
                payloadLength = frameLength - checksumSize - 8
            }
            break
        case "HEART_BEAT":
        case "DP_QUERY":
        case "DP_QUERY_NEW":
            fCommand = ""
            payloadStart = 20
            payloadLength = frameLength - checksumSize - 8
            break
    }

    String plainTextMessage = ""

    if (incomingData[sofIndex + payloadStart] == (byte)'{') {
        plainTextMessage = new String(incomingData, "UTF-8")[(sofIndex + payloadStart)..(sofIndex + payloadStart + payloadLength - 1)]
    } else {
        plainTextMessage = decryptPayload(incomingData as byte[], useKey, sofIndex + payloadStart, payloadLength)
    }

    if (logEnable) log.debug "Decrypted: ${plainTextMessage}"

    Object status = [:]

    if (plainTextMessage.indexOf('dps') != -1) {
        def jsonSlurper = new groovy.json.JsonSlurper()
        status = jsonSlurper.parseText(plainTextMessage.substring(plainTextMessage.indexOf('{')))
    }

    // Handle key response
    if (frameTypes[frameType] == "KEY_RESP") {
        byte[] responseOnKeyResponse
        byte[] remoteNonce
        (responseOnKeyResponse, remoteNonce) = decodeIncomingKeyResponse(plainTextMessage)
        state.session_step = "step3"
        socket_write(responseOnKeyResponse)

        state.sessionKey = calculateSessionKey(remoteNonce)
        state.session_step = "final"
        state.HaveSession = true

        sendEvent(name: "presence", value: "present")
        runInMillis(100, sendAll)

        if (heartBeatMethod) {
            runIn(20, heartbeat)
        } else {
            runIn(30, socketStatus, [data: "disconnect: pipe closed"])
        }
        return [:]
    }

    if (frameTypes[frameType] == "STATUS_RESP" && settings.tuyaProtVersion == "34") {
        status = status["data"]
    }

    if (frameTypes[frameType] == "HEART_BEAT") {
        unschedule(socketStatus)
        runIn(18, heartbeat)
    }

    return status
}

def decryptPayload(byte[] data, byte[] key, start, length) {
    ByteArrayOutputStream payloadStream = new ByteArrayOutputStream()
    for (i = 0; i < length; i++) {
        payloadStream.write(data[start + i])
    }
    byte[] payloadByteArray = payloadStream.toByteArray()
    boolean useB64 = settings.tuyaProtVersion == "31"
    return decrypt_bytes(payloadByteArray, key, useB64)
}

def decodeIncomingKeyResponse(String incomingData, byte[] useKey=getRealLocalKey(), Short useMsgSequence=null) {
    byte[] remoteNonce = incomingData[0..15].getBytes()

    Mac sha256HMAC = Mac.getInstance("HmacSHA256")
    SecretKeySpec key = new SecretKeySpec(useKey, "HmacSHA256")
    sha256HMAC.init(key)
    sha256HMAC.update(remoteNonce, 0, remoteNonce.size())
    byte[] digest = sha256HMAC.doFinal()

    byte[] message = generateGeneralMessageV3_4(digest, getFrameTypeId("KEY_FINAL"), useKey, useMsgSequence)
    return [message, remoteNonce]
}

def calculateSessionKey(byte[] remoteNonce, String useLocalNonce=null, byte[] key=getRealLocalKey()) {
    byte[] localNonce = useLocalNonce==null? getLocalNonce().getBytes() : useLocalNonce.getBytes()
    byte[] calKey = new byte[16]

    for (int i = 0; i < 16; i++) {
        calKey[i] = (byte)(localNonce[i] ^ remoteNonce[i])
    }

    def sessKeyHEXString = encrypt(calKey, key, false)
    return hubitat.helper.HexUtils.hexStringToByteArray(sessKeyHEXString[0..31])
}

def get_session(tuyaVersion) {
    if (tuyaVersion.toInteger() <= 33) {
        if (heartBeatMethod) {
            runIn(20, heartbeat)
        } else {
            runIn(30, socketStatus, [data: "disconnect: pipe closed"])
        }

        boolean connected = socket_connect()
        if (connected) {
            sendEvent(name: "presence", value: "present")
        }
        return connected
    }

    def current_session_state = state.session_step ?: "step1"

    switch (current_session_state) {
        case "step1":
            socket_connect()
            state.session_step = "step2"
            socket_write(generateKeyStartMessage())
            runInMillis(750, get_session_timeout)
            break
        case "final":
            return true
    }

    return false
}

def get_session_timeout() {
    log.error "Session timeout at ${state.session_step}"
    state.session_step = "step1"
}

def generateLocalNonce(Integer length=16) {
    String alphabet = (('A'..'N')+('P'..'Z')+('a'..'k')+('m'..'z')+('2'..'9')).join()
    return new Random().with {
        (1..length).collect { alphabet[nextInt(alphabet.length())] }.join()
    }
}

String getLocalNonce() {
    if (state.LocalNonce == null) {
        state.LocalNonce = generateLocalNonce()
    }
    return state.LocalNonce
}

byte[] generateKeyStartMessage(String useLocalNonce=null, byte[] useKey=getRealLocalKey(), Short useMsgSequence=null) {
    def payload = useLocalNonce ?: getLocalNonce()
    def encrypted_payload = hubitat.helper.HexUtils.hexStringToByteArray(encrypt(payload, useKey, false))

    def packed_message = new ByteArrayOutputStream()
    Short msgSequence = useMsgSequence ?: getNewMessageSequence()

    packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray("000055aa0000"))
    packed_message.write(msgSequence >> 8)
    packed_message.write(msgSequence)
    packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray("000000"))
    packed_message.write(getFrameTypeId("KEY_START"))
    packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray("000000"))
    packed_message.write(encrypted_payload.size() + 32 + 4)
    packed_message.write(encrypted_payload)

    Mac sha256_hmac = Mac.getInstance("HmacSHA256")
    sha256_hmac.init(new SecretKeySpec(useKey, "HmacSHA256"))
    sha256_hmac.update(packed_message.toByteArray(), 0, packed_message.size())

    packed_message.write(sha256_hmac.doFinal())
    packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray("0000aa55"))

    return packed_message.toByteArray()
}

def generateGeneralMessageV3_4(byte[] data, Integer cmd, byte[] useKey=getRealLocalKey(), Short useMsgSequence=null) {
    def encrypted_payload = hubitat.helper.HexUtils.hexStringToByteArray(encrypt(data, useKey, false))

    def packed_message = new ByteArrayOutputStream()
    Short msgSequence = useMsgSequence ?: getNewMessageSequence()

    packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray("000055aa0000"))
    packed_message.write(msgSequence >> 8)
    packed_message.write(msgSequence)
    packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray("000000"))
    packed_message.write(cmd)
    packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray("000000"))
    packed_message.write(encrypted_payload.size() + 32 + 4)
    packed_message.write(encrypted_payload)

    Mac sha256_hmac = Mac.getInstance("HmacSHA256")
    sha256_hmac.init(new SecretKeySpec(useKey, "HmacSHA256"))
    sha256_hmac.update(packed_message.toByteArray(), 0, packed_message.size())

    packed_message.write(sha256_hmac.doFinal())
    packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray("0000aa55"))

    return packed_message.toByteArray()
}

def payload() {
    return [
        "v3.1_v3.3": [
            "status": ["hexByte": "0a", "command": ["gwId":"", "devId":"", "uid":"", "t":""]],
            "set": ["hexByte": "07", "command": ["devId":"", "uid": "", "t": ""]],
            "hb": ["hexByte": "09", "command": ["gwId":"", "devId":""]],
            "prefix_nr": "000055aa0000",
            "suffix": "0000aa55"
        ],
        "v3.4": [
            "status": ["hexByte": "10", "command": [:]],
            "set": ["hexByte": "0d", "command": ["protocol":5, "t":"", "data":""]],
            "hb": ["hexByte": "09", "command": ["gwId":"", "devId":""]],
            "prefix_nr": "000055aa0000",
            "suffix": "0000aa55"
        ]
    ]
}

byte[] generate_payload(String command, def data=null, String timestamp=null, byte[] localkey=getRealLocalKey(), String devid=settings.devId, String tuyaVersion=settings.tuyaProtVersion, Short useMsgSequence=null) {
    def payloadFormat = tuyaVersion == "34" ? "v3.4" : "v3.1_v3.3"

    if (state.sessionKey != null) {
        localkey = state.sessionKey
    }

    def json_data = payload()[payloadFormat][command]["command"].clone()

    if (json_data.containsKey("gwId")) json_data["gwId"] = devid
    if (json_data.containsKey("devId")) json_data["devId"] = devid
    if (json_data.containsKey("uid")) json_data["uid"] = devid
    if (json_data.containsKey("t")) {
        json_data["t"] = timestamp ?: ((new Date().getTime()/1000).toInteger().toString())
    }

    if (data != null && data != [:]) {
        if (json_data.containsKey("data")) {
            json_data["data"] = ["dps": data]
        } else {
            json_data["dps"] = data
        }
    }

    def json = new groovy.json.JsonBuilder(json_data)
    def json_payload = groovy.json.JsonOutput.toJson(json.toString())
        .replaceAll("\\\\", "")
        .replaceFirst("\"", "")[0..-2]

    ByteArrayOutputStream contructed_payload = new ByteArrayOutputStream()

    if (tuyaVersion == "31") {
        if (command != "status") {
            def encrypted_payload = encrypt(json_payload, localkey)
            def preMd5String = "data=${encrypted_payload}||lpv=3.1||${new String(localkey, 'UTF-8')}"
            def hexdigest = generateMD5(preMd5String)
            json_payload = "3.1${hexdigest[8..-9]}${encrypted_payload}"
        }
        contructed_payload.write(json_payload.getBytes())
    } else if (tuyaVersion == "33") {
        def encrypted_payload = encrypt(json_payload, localkey as byte[], false)
        if (command != "status" && command != "hb") {
            contructed_payload.write("3.3\0\0\0\0\0\0\0\0\0\0\0\0".getBytes())
        }
        contructed_payload.write(hubitat.helper.HexUtils.hexStringToByteArray(encrypted_payload))
    } else if (tuyaVersion == "34") {
        if (command != "status" && command != "hb") {
            json_payload = "3.4\0\0\0\0\0\0\0\0\0\0\0\0" + json_payload
        }
        def encrypted_payload = encrypt(json_payload, localkey as byte[], false)
        contructed_payload.write(hubitat.helper.HexUtils.hexStringToByteArray(encrypted_payload))
    }

    def payload_len = contructed_payload.size() + 4 + (tuyaVersion == "34" ? 32 : 4)

    Short msgSequence = useMsgSequence ?: getNewMessageSequence()

    def output = new ByteArrayOutputStream()
    output.write(hubitat.helper.HexUtils.hexStringToByteArray(payload()[payloadFormat]["prefix_nr"]))
    output.write(msgSequence >> 8)
    output.write(msgSequence)
    output.write(hubitat.helper.HexUtils.hexStringToByteArray("000000"))
    output.write(hubitat.helper.HexUtils.hexStringToByteArray(payload()[payloadFormat][command]["hexByte"]))
    output.write(hubitat.helper.HexUtils.hexStringToByteArray("000000"))
    output.write(payload_len)
    output.write(contructed_payload.toByteArray())

    byte[] buf = output.toByteArray()

    if (tuyaVersion == "34") {
        Mac sha256_hmac = Mac.getInstance("HmacSHA256")
        sha256_hmac.init(new SecretKeySpec(localkey as byte[], "HmacSHA256"))
        sha256_hmac.update(buf, 0, buf.size())
        output.write(sha256_hmac.doFinal())
    } else {
        def crc32 = CRC32b(buf, buf.size()) & 0xffffffff
        def hex_crc = Long.toHexString(crc32).padLeft(8, '0')
        output.write(hubitat.helper.HexUtils.hexStringToByteArray(hex_crc))
    }

    output.write(hubitat.helper.HexUtils.hexStringToByteArray(payload()[payloadFormat]["suffix"]))

    return output.toByteArray()
}

// ==================== CRYPTO ====================
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import java.security.MessageDigest

def encrypt(def plainText, byte[] secret, encodeB64=true) {
    def cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"))

    if (encodeB64) {
        return cipher.doFinal(plainText.getBytes("UTF-8")).encodeBase64().toString()
    } else {
        if (plainText instanceof String) {
            return cipher.doFinal(plainText.getBytes("UTF-8")).encodeHex().toString()
        } else {
            return cipher.doFinal(plainText).encodeHex().toString()
        }
    }
}

def decrypt_bytes(byte[] cypherBytes, def secret, decodeB64=false) {
    def cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")

    SecretKeySpec key
    if (secret instanceof String) {
        key = new SecretKeySpec(secret.replaceAll('&lt;', '<').getBytes(), "AES")
    } else {
        key = new SecretKeySpec(secret as byte[], "AES")
    }

    cipher.init(Cipher.DECRYPT_MODE, key)

    if (decodeB64) {
        cypherBytes = cypherBytes.decodeBase64()
    }

    return new String(cipher.doFinal(cypherBytes), "UTF-8")
}

def generateMD5(String s) {
    MessageDigest.getInstance("MD5").digest(s.bytes).encodeHex().toString()
}

def CRC32b(bytes, length) {
    def crc = 0xFFFFFFFF
    for (i = 0; i < length; i++) {
        def b = Byte.toUnsignedInt(bytes[i])
        crc = crc ^ b
        for (j = 7; j >= 0; j--) {
            def mask = -(crc & 1)
            crc = (crc >> 1) ^ (0xEDB88320 & mask)
        }
    }
    return ~crc
}
