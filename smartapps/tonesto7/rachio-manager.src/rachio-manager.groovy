/**
 *  Rachio (Connect) Smart App
 *
 *  Copyright� 2018 Franz Garsombke
 *  With the Assistance of Anthony Santilli (@tonesto7)
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
 *	Modified: 05-09-2018
 */

import groovy.json.*
import java.text.SimpleDateFormat

definition(
	name: "Rachio Manager",
	namespace: "tonesto7",
	author: "Anthony Santilli",
	description: "Community version of the SmartThings Rachio Integration.",
	category: "Green Living",
	iconUrl: "https://s3-us-west-2.amazonaws.com/rachio-media/smartthings/Rachio-logo-100px.png",
	iconX2Url: "https://s3-us-west-2.amazonaws.com/rachio-media/smartthings/Rachio-logo-200px.png",
	iconX3Url: "https://s3-us-west-2.amazonaws.com/rachio-media/smartthings/Rachio-logo-300px.png",
	singleInstance: true,
	usesThirdPartyAuthentication: true,
	pausable: false)

preferences {
	page(name: "startPage")
	page(name: "apiKeyPage")
	page(name: "authPage")
	page(name: "noOauthPage")
	page(name: "devicePage")
	page(name: "supportPage")
}

mappings {
	path("/rachioReceiver") { action: [ POST: "rachioReceiveHandler" ] }
}

def appVer() { return "1.0.2" }

def appInfoSect()	{
	section() {
		def str = ""
		str += "${app?.name}"
		str += "\nCopyright\u00A9 2018 Anthony Santilli"
		str += "\nVersion: ${appVer()}"
		paragraph str, image: "https://s3-us-west-2.amazonaws.com/rachio-media/smartthings/Rachio-logo-100px.png"
	}
}

def startPage() {
	getAccessToken()
	if(!atomicState?.accessToken) {
		noOauthPage()
	} else if(atomicState?.authToken) {
		devicePage()
	} else { authPage() }
}


def noOauthPage() {
	return dynamicPage(name: "noOauthPage", title: "Oauth Not Enabled", uninstall: true) {
		appInfoSect()
		section() {
			paragraph "Oauth is Not Enabled for this SmartApp.  Please return to the IDE and Enable OAuth under App Settings", required: true, state: null
		}
		removeSect()
	}
}

def authPage()  {
	//log.debug "authPage()"
	def description = null
	def uninstallAllowed = false
	def oauthTokenProvided = false

	if(settings?.authKey && settings?.authKey.toString()?.length() > 10 && settings?.authKey != atomicState?.authToken) {
		atomicState?.authToken = settings?.authKey
		oauthTokenProvided = true
	}
	if(atomicState?.authToken) {
		getRachioDeviceData(true)
		def usrName = atomicState?.userName ?: ""
		description = usrName ? "You are signed in as $usrName" : "You are connected."
		uninstallAllowed = true
		oauthTokenProvided = true
	} else {
		description = "Login to Rachio Web App to get your API Key"
	}
	
	if (!oauthTokenProvided) { log.info "No Rachio AuthToken Found... Please Login to Web App > Account Settings > Get API Key..." }
	def authPara = !oauthTokenProvided ? "Please Login to Web App\n� Tap on More Tab\n� Click on Account Settings\n� Click Get API Key\n� Tap the Copy Icon\n� Press the back button\n� Paste the key in the input below" : "Tap Next to setup your sprinklers."
	return dynamicPage(name: "authPage", title: "Auth Page", nextPage: (oauthTokenProvided ? "devicePage" : null), uninstall: uninstallAllowed) {
		appInfoSect()
		section() {
			paragraph authPara
			href url: "https://app.rach.io", style: "external", required: (!oauthTokenProvided), state: (oauthTokenProvided ? "complete" : ""), title: "Rachio", description: description
			href "apiKeyPage", title: "Enter your API Key", description: (authKey ? "API Key Entered" : "Tap to Enter API Key"), state: (authKey ? "complete" : null), required: true
		}
		if(uninstallAllowed) { removeSect() }
	}
}

def apiKeyPage() {
	return dynamicPage(name: "apiKeyPage", title: "API Key", install: false, uninstall: false) {
		section() {
			input "authKey", "text", title: "API Key", required: true, description: "Paste the API Key Here...", submitOnChange: true
		}
	}
}

def removeSect() {
	remove("Remove this App and Devices!", "WARNING!!!", "Last Chance to Stop!\nThis action is not reversible\n\nThis App and All Devices will be removed")
}
// This method is called after "auth" page is done with Oauth2 authorization, then page "deviceList" with content of devicePage()
def devicePage() {
	//log.trace "devicePage()..."
	if(!atomicState?.authToken) {
		log.debug "No accesstoken"
		return
	}
	// Step 1: get (list) of available devices associated with the login account.
	def devData = getRachioDeviceData()
	def devices = getDeviceInputEnum(devData)
	//log.debug "rachioDeviceList() device list: ${devices}"

	//step2: render the page for user to select which device
	return dynamicPage(name: "devicePage", title: "${(atomicState?.authToken && atomicState?.devices && atomicState?.selectedZones) ? "Select" : "Manage"} Your Devices", install: true, uninstall: true) {
		appInfoSect()
		section("Device Selection:"){
			paragraph "Select your sprinkler controller and zones."
			input(name: "sprinklers", title: "Select Your Sprinkler", type: "enum", description: "Tap to Select", required: true, multiple: false, options: devices, submitOnChange: true,
					image: (atomicState?.modelInfo ? atomicState?.modelInfo.img : ""))
			if(settings?.sprinklers) {
				atomicState?.deviceId = settings?.sprinklers
				updateHwInfoMap(devData?.devices)
				def zoneData = zoneSelections(devData)
				input(name: "selectedZones", title: "Select Your Zones", type: "enum", description: "Tap to Select", required: true, multiple: true, options: zoneData, submitOnChange: true)
				if(settings?.selectedZones) {
					def d = devData?.devices?.find { it?.id == settings?.sprinklers }
					if(d) { devDesc(d) }
				}
			}
		}
		if(settings?.sprinklers) {
			section("Preferences:") {
				input(name: "pauseInStandby", title: "Disable Actions while in Standby?", type: "bool", defaultValue: true, multiple: false, submitOnChange: true,
						description: "Allow your device to be disabled in SmartThings when you place your controller in Standby Mode...")
				paragraph "Select the Duration time to be used for manual Zone Runs (This can be changed under each zones device page)"
				input(name: "defaultZoneTime", title: "Default Zone Runtime (Minutes)", type: "number", description: "Tap to Modify", required: false, defaultValue: 10, submitOnChange: true)
			}
		}
		section() {
			href "supportPage", title: "Rachio Support", description: ""
			href "authPage", title: "Manage Login", description: ""
		}
		removeSect()
	}
}

def devDesc(dev) {
	if(dev) {
		// log.debug "dev: $dev"
		def str = ""
		def zoneCnt = dev?.zones?.findAll { it?.id in settings?.selectedZones }?.size() ?: 0
		str += "${atomicState?.installed ? "Installed" : "Installing"} Device:\n${atomicState?.modelInfo[dev?.id]?.desc}"
		str += "\n($zoneCnt) Zone(s) ${atomicState?.installed ? "are selected" :  "will be installed"}"
		paragraph "${str}", state: "complete", image: (atomicState?.modelInfo[dev?.id]?.img ?: "")
	}
}

def supportPage() {
	return dynamicPage(name: "supportPage", title: "Rachio Support", install: false, uninstall: false) {
		section() {
			href url: getSupportUrl(), style:"embedded", title:"Rachio Support (Web)", description:"", state: "complete",
					image: "http://rachio-media.s3.amazonaws.com/images/icons/icon-support.png"
			href url: getCommunityUrl(), style:"embedded", title:"Rachio Community (Web)", description:"", state: "complete",
					image: "http://d33v4339jhl8k0.cloudfront.net/docs/assets/5355b85be4b0d020874de960/images/58333550903360645bfa6cf8/file-Er3y7doeam.png"
		}
	}
}

def zoneSelections(devData, multiDev=false) {
	//log.debug "zoneSelections: $devData"
	def res = multiDev ? [] : [:]
	if(!devData) { return res }
	devData?.devices.sort {it?.name}.each { dev ->
		if(multiDev) {
			def i = 0
			if(dev?.id in settings?.sprinklers) {
				def hwData = getHardwareInfo(dev?.model)
				def zones = []
				dev?.zones?.sort { it?.zoneNumber }.each { zon ->
					zones << ["key":zon?.id.toString(), "value":zon?.name?.toString()]
				}
				res << [title : dev?.name?.toString(), order : i, image : hwData?.img, values: zones]
			}
		} else {
			if(dev?.id == settings?.sprinklers) {
				dev?.zones?.sort {it?.zoneNumber }.each { zone ->
					def str = (zone?.enabled == true) ? "" : " (Disabled)"
					//log.debug "zoneId: $zone.id"
					def adni = [zone?.id].join('.')
					res[adni] = "${zone?.name}$str"
				}
			}
		}
	}
	return res
}

// This was added to handle missing oauth on the smartapp and notifying the user of why it failed.
def getAccessToken() {
	try {
		if(!atomicState?.accessToken) {
			atomicState?.accessToken = createAccessToken()
			//log.debug "Created ST Access Token... ${atomicState?.accessToken}" //Hiding this from Release
		}
		else { return true }
	}
	catch (ex) {
		def msg = "Error: OAuth is not Enabled for the Rachio (Connect) application!!!.  Please click remove and Enable Oauth under the SmartApp App Settings in the IDE..."
		// sendPush(msg)
		log.warn "getAccessToken Exception | ${msg}"
		return false
	}
}

def getRachioDeviceData(noData=false) {
	//log.trace "getRachioDevicesData($noData)..."

	//Step1: GET account info "userId"
	atomicState.userId = getUserId();
	if (!atomicState?.userId) {
		log.error "No user Id found exiting"
		return
	}
	def userInfo = getUserInfo(atomicState?.userId)
	//log.debug "userInfo: ${userInfo}"
	atomicState?.userName = userInfo?.username

	if(!noData) { return userInfo }
}

def getDeviceInputEnum(data) {
	//Step3: Obtain device information for a location
	def devices = [:]
	if(!data) { return devices }
	data?.devices.sort { it?.name }.each { sid ->
	   //log.info "systemId: ${sid.id}"
	   def dni = sid?.id
	   devices[dni] = sid?.name
	   //log.info "Found sprinkler with dni(locationId.gatewayId.systemId.zoneId): $dni and displayname: ${devices[dni]}"
	}
	//log.info "getRachioDevicesData() >> sprinklers: $devices"
	return devices
}

def buildDeviceMap(userData, onlySelected=false) {
	def devMap = [:]
	def selDevs = settings?.sprinklers
	if(!userData || !selDevs) { return devMap }
	userData?.devices?.each { dev ->
		if(onlySelected && !dev?.id in selDevs) { return }
		def zoneMap = zoneMap(dev?.zones, onlySelected)
		def adni = [dev?.id].join('.')
		devMap[adni] = ["name":dev?.name, "zones":zoneMap]
	}
	//log.debug "devMap: $devMap"
	return devMap
}

def zoneMap(data, onlySelected=false) {
	def zoneMap = [:]
	if(data) {
		data?.sort { it?.zoneNumber }.each { zn ->
			if(onlySelected && !zn?.id in selDevs) { return }
			def zdni = [zn?.id].join('.')
			zoneMap[zdni] = zn?.name
		}
	}
	return zoneMap
}


def getUserInfo(userId) {
	//log.trace "getUserInfo ${userId}"
	return _httpGet("person/${userId}");
}

def getUserId() {
	//log.trace "getUserId()"
	def res = _httpGet("person/info");
	if (res) {
		return res?.id;
	}
	return null
}

void updateHwInfoMap(data, multiDev=false) {
	def result = [:]
	if(data && settings?.sprinklers) {
		def results = null
		if(multiDev) {
			results = data?.findAll { it?.id in settings?.sprinklers }
		} else {
			results = data?.findAll { it?.id == settings?.sprinklers }
		}
		results?.each { dev ->
			result[dev?.id] = getHardwareInfo(dev?.model)
		}
	}
	atomicState?.modelInfo = result
}

def getHardwareInfo(val) {
	def res = []
	def model = null
	def desc = null
	def img = ""
	switch(val) {
		case "GENERATION1_8ZONE":
			model = "8ZoneV1"
			desc = "8-Zone (Gen 1)"
			img = "https://s3-us-west-2.amazonaws.com/rachio-media/smartthings/8zone_v1.png"
			break
		case "GENERATION1_16ZONE":
			model = "16ZoneV1"
			desc = "16-Zone (Gen 1)"
			img = "https://s3-us-west-2.amazonaws.com/rachio-media/smartthings/16zone_v1.png"
			break
		case "GENERATION2_8ZONE":
			model = "8ZoneV2"
			desc = "8-Zone (Gen 2)"
			img = "https://s3-us-west-2.amazonaws.com/rachio-media/smartthings/8zone_v2.jpg"
			break
		case "GENERATION2_16ZONE":
			model = "16ZoneV2"
			desc = "16-Zone (Gen 2)"
			img = "https://s3-us-west-2.amazonaws.com/rachio-media/smartthings/16zone_v2.jpg"
			break
		case "GENERATION3_8ZONE":
			model = "8ZoneV3"
			desc = "8-Zone (Gen 3)"
			img = "https://s3-us-west-2.amazonaws.com/rachio-media/smartthings/8zone_v3.jpg"
			break
		case "GENERATION3_16ZONE":
			model = "16ZoneV3"
			desc = "16-Zone (Gen 3)"
			img = "https://s3-us-west-2.amazonaws.com/rachio-media/smartthings/16zone_v3.jpg"
			break
	}
	//log.debug "Model: $model | Desc: $desc"
	res = ["desc":desc, "model":model, "img":img]
	return res
}

def _httpGet(subUri) {
	//log.debug "_httpGet($subUri)"
	try {
		def params = [
			uri: "${apiEndpoint}/1/public/${subUri}",
			headers: ["Authorization": "Bearer ${atomicState.authToken}"]
		]
		httpGet(params) { resp ->
			if(resp.status == 200) {
				return resp?.data
			} else {
				//refresh the auth token
				if (resp?.status == 500 && resp?.data?.status?.code == 14) {
					log.debug "Storing the failed action to try later"
					data.action = "getRachioDeviceData"
					log.debug "Refreshing your authToken!"
					// refreshAuthToken()
				} else {
					log.error "Authentication error, invalid authentication method, lack of credentials, etc."
				}
			  return null
			}
		}
	} catch (ex) {
		if(ex instanceof groovyx.net.http.HttpResponseException) {
			if(ex?.response) {
				log.error("httpGet() Response Exception | Status: ${ex?.response?.status} | Data: ${ex?.response?.data}")
			}
		} else {
			log.error "_httpGet exception: ${ex.message}"
		}
	}
}

def getDisplayName(iroName, zname) {
	if(zname) {
		return "${iroName}:${zname}"
	} else {
		return "Rachio"
	}
}

//Section3: installed, updated, initialize methods
def installed() {
	log.trace "Installed with settings: ${settings}"
	runIn(10, "initialize", [overwrite: true])
	atomicState?.installed = true
}

def updated() {
	log.debug "Updated with settings: ${settings}"
	unsubscribe()
	runIn(10, "initialize", [overwrite: true])
}

def initialize() {
	log.trace "initialized..."
	unschedule()
	scheduler()
	//Creates the selectedDevice and selectedZones maps in state
	updateDevZoneStates()

	addRemoveDevices()

	if(initWebhook()) { }
	poll()
	//send activity feeds to tell that device is connected
	def notificationMessage = "is connected to SmartThings"
	sendActivityFeeds(notificationMessage)
	atomicState.timeSendPush = null

	subscribe(app, onAppTouch)
}

def uninstalled() {
	log.trace "uninstalled() called... removing smartapp and devices"
	unschedule()

	//Remove any existing webhooks before uninstall...
	removeWebhooks()

	if(addRemoveDevices(true)) {
		//Revokes Smartthings endpoint token...
		revokeAccessToken()
		//Revokes Rachio Auth Token
		if(atomicState?.authToken) {
		  atomicState?.authToken = null
		}
	}
}

def onAppTouch(event) {
	poll()
}

def scheduler() {
	runEvery15Minutes("heartbeat")
	//runEvery30Minutes("heartbeat")
}

def heartbeat() {
	log.trace "heartbeat 15 minute keep alive poll()..."
	poll()
}

def getAppEndpointUrl(subPath)	{ return "${apiServerUrl("/api/smartapps/installations/${app.id}${subPath ? "/${subPath}" : ""}?access_token=${atomicState.accessToken}")}" }

//Subscribes to the selected controllers API events that will be used to trigger a poll
def initWebhook() {
	//log.trace "initWebhook..."
	def result = false
	def whId = atomicState?.webhookId
	def cmdType = whId == null ? "post" : "put"
	def apiWebhookUrl = "${rootApiUrl()}/notification/webhook"
	def endpointUrl = getAppEndpointUrl("rachioReceiver")
	def bodyData
	if(!whId) { bodyData = ["device":["id":settings?.sprinklers], "externalId":app.id, "url": endpointUrl, "eventTypes":webhookEvtTypes()] }
	else { bodyData = ["id":whId, "externalId":app.id, "url": endpointUrl, "eventTypes":webhookEvtTypes()] }
	def jsonData = new JsonOutput().toJson(bodyData)
	if(webhookHttp(apiWebhookUrl, jsonData, cmdType)) {
		log.info "${cmdType == "post" ? "Created" : "Updated"} device api webhook subscription successfully!!!"
		result = true
	}
	return result
}

//This isn't used for anything other than to return the webhooks for the device
def getWebhooks(devId) {
	def data = _httpGet("notification/${devId}/webhook")
	def res = null
	if(data) {
		data?.each { wh ->
			if(wh?.externalId == app?.id) {
				res = wh
			}
		}
	}
	return res
}

//Removes the webhook subscription for the device.
void removeWebhooks() {
	def webhookId = atomicState?.webhookId
	if(webhookId) {
		if(webhookHttp("${rootApiUrl()}/notification/webhook/${webhookId}", "", "delete")) {
			log.warn "Removed API Webhook Subscription for (${settings?.sprinklers})"
			atomicState?.webhookId = null
		}
	}
}

//Returns the available event types to subscribe to.
def webhookEvtTypes() {
	def typeIds = []
	def okTypes = ["DEVICE_STATUS_EVENT", "ZONE_STATUS_EVENT"] //, "WEATHER_INTELLIGENCE_EVENT", "RAIN_DELAY_EVENT"]
	def data = _httpGet("notification/webhook_event_type")
	if(data) {
		typeIds = data?.findAll { it?.name in okTypes }.collect { ["id":it?.id?.toString()] }
	}
	return typeIds
}

//Handles the http requests for the webhook methods
def webhookHttp(url, jsonBody, type=null) {
	log.trace "webhookHttp($url, $jsonBody, $type)"
	def returnStatus = false
	def response = null
	// def data = new JsonBuilder(jsonBody)
	def cmdParams = [
		uri: url,
		contentType: "application/json",
		requestContentType: "application/json",
		headers: ["Authorization": "Bearer ${atomicState?.authToken}"],
		body: jsonBody
	]
	try {
		if(type == "post") {
			httpPost(cmdParams) { resp ->
				response = resp
			}
		}
		else if(type == "put") {
			httpPut(cmdParams) { resp ->
				response = resp
			}
		}
		else if(type == "delete") {
			httpDelete(cmdParams) { resp ->
				response = resp
			}
		}
		if(response) {
			log.debug "response.status: ${response?.status} | data: ${response?.data}"
			if(response?.status in [200, 201, 204]) {
				returnStatus = true
				if(type in ["put", "post"]) {
					def whId = response?.data?.id
					atomicState?.webhookId = whId
				} else if(type == "delete") {
					atomicState?.webhookId = null
				}
			} else {
				//refresh the auth token
				if (response?.status == 401) {
					log.debug "Refreshing your authToken!"
					// refreshAuthToken()
				} else {
					log.error "Authentication error, invalid authentication method, lack of credentials, etc."
				}
			}
		} else { return returnStatus }
	} catch(Exception e) {
		log.error "webhookHttp Exception Error: ", e
	}
	return returnStatus
}

// This is the endpoint the webhook sends the events to...
def rachioReceiveHandler() {
    def reqData = request.JSON
	if(reqData?.size() || reqData == [:]) {
		//log.trace "eventDatas: ${reqData?.eventDatas}"
		log.trace "Rachio Device Event Received - Requesting New Data from API"
		poll()
	}
}

def getDeviceIds() {
	return settings?.sprinklers ?: null
}

def getZoneIds() {
	return settings?.selectedZones ?: null
}

def getZoneData(userId, zoneId) {
	return _httpGet("person/${userId}/${zoneId}")
}

void updateDevZoneStates() {
	def devMap = [:]
	def zoneMap = [:]
	def userInfo = getUserInfo(atomicState?.userId)
	userInfo?.devices?.each { dev ->
		if(dev?.id == settings?.sprinklers) {
			def adni = [dev?.id].join('.')
			dev?.zones?.each { zone ->
			   if(zone?.id in settings?.selectedZones) {
					def zdni = [zone?.id].join('.')
					zoneMap[zdni] = zone?.name
				}
			}
			devMap[adni] = dev?.name
		}
	}
	atomicState?.selectedDevice = devMap
	atomicState?.selectedZones = zoneMap
}

def getDeviceInfo(devId) {
	//log.trace "getDeviceInfo..."
	return _httpGet("device/${devId}")
}

def getCurSchedule(devId) {
	//log.trace "getCurSchedule..."
	return _httpGet("device/${devId}/current_schedule")
}

def getDeviceData(devId) {
	//log.trace "getDeviceData($devId)..."
	return _httpGet("device_with_current_schedule/${devId}")
}

def rootApiUrl() { return "https://api.rach.io/1/public" }

def addRemoveDevices(uninst=false) {
	//log.trace "addRemoveDevices($uninst)..."
	try {
		def delete = []
		if(uninst == false) {
			def devsInUse = []
			//sprinklers is selected by user on DeviceList page
			def controller = atomicState?.selectedDevice?.collect { dni ->
				//Check if the discovered sprinklers are already initiated with corresponding device types.
				def d = getChildDevice(dni?.key)
				if(!d) {
					removeWebhooks()
					d = addChildDevice("tonesto7", getChildContName(), dni?.key, null, [label: getDeviceLabelStr(dni?.value)])
					d.completedSetup = true
					d.take()
					log.info "Controller Device Created: (${d?.displayName}) with id: [${dni?.key}]"
				} else {
					//log.debug "found ${d?.displayName} with dni: ${dni?.key} already exists"
				}
				devsInUse += dni.key
				return d
			}

			def zoneDevices = atomicState?.selectedZones?.collect { dni ->
				//Check if the discovered sprinklers are already initiated with corresponding device types.
				def d2 = getChildDevice(dni?.key)
				if(!d2) {
					d2 = addChildDevice("tonesto7", getChildZoneName(), dni?.key, null, [label: getDeviceLabelStr(dni?.value)])
					d2.completedSetup = true
					d2.take()
					log.info "Zone Device Created: (${d2?.displayName}) with id: [${dni?.key}]"
				} else {
					//log.debug "found ${d2?.displayName} with dni: ${dni?.key} already exists"
				}
				devsInUse += dni.key
				return d2
			}
			//log.debug "devicesInUse: ${devsInUse}"
			delete = app.getChildDevices(true).findAll { !devsInUse?.toString()?.contains(it?.deviceNetworkId) }
		} else {
			atomicState?.selectedDevice = []
			atomicState?.selectedZoneMap = []
			delete = app.getChildDevices(true)
		}
		if(delete?.size() > 0) {
			log.warn "Device Delete: ${delete} | Removing (${delete?.size()}) Devices..."
			delete.each {
				deleteChildDevice(it.deviceNetworkId)
				log.warn "Deleted the Device: ${it?.displayName}"
			}
		}
		return true
	} catch (ex) {
		if(ex instanceof physicalgraph.exception.ConflictException) {
			def msg = "Error: Can't Delete App because Devices are still in use in other Apps, Routines, or Rules.  Please double check before trying again."
			log.warn "addRemoveDevices Exception | $msg"
		}
		else if(ex instanceof physicalgraph.app.exception.UnknownDeviceTypeException) {
			def msg = "Error: Device Handlers are likely Missing or Not Published.  Please verify all device handlers are present before continuing."
			log.warn "addRemoveDevices Exception | $msg"
		}
		else { log.error "addRemoveDevices Exception: ${ex}" }
		return false
	}
}

def getDeviceLabelStr(name) {
	return "Rachio - ${name}"
}

def getTimeSinceInSeconds(time) {
	if(!time) { return 10000 }
	return (int) (now() - time)/1000
}

//Section4: polling device info methods
void poll(child=null) {
	def lastPollSec = getTimeSinceInSeconds(atomicState?.lastPollDt)
	log.info "${child ? "[${child.device?.label}] is requesting the parent poll for new data!!!" : "${app.label} -- Polling API for New Data -- Last Update was ($lastPollSec seconds ago)"}"
	if(atomicState?.inStandbyMode == null) { atomicState?.inStandbyMode = false }
	if(lastPollSec < 2) {
		runIn(3, "poll", [overwrite: true])
		//log.warn "Delaying poll... It's too soon to request new data"
		return
	}
	def devData = getDeviceData(atomicState?.deviceId)
	// log.debug devData
	def devices = app.getChildDevices(true)
	log.info "Updating (${devices?.size()}) child devices..."
	devices?.each { dev ->
		pollChild(dev, devData)
	}
	atomicState?.lastPollDt = now()
}

//this method is called by (child) device type, to reply (Map) rachioData to the corresponding child
def pollChild(child, devData) {
	//poll data from 3rd party cloud
	if (pollChildren(child, devData)){
		//generate event for each (child) device type identified by different dni
	}
}

def pollChildren(child, devData) {
	//log.trace "updating child device (${child?.label})" // | ${child?.device?.deviceNetworkId})"
	try {
		if(child && devData) {
			def dni = child?.device?.deviceNetworkId
			def d = child
			def devLabel = d?.label?.toString()
			def schedData = devData.currentSchedule
			def devStatus = devData
			def rainDelay = getCurrentRainDelay(devStatus)
			def status = devStatus?.status
			def pauseInStandby = settings?.pauseInStandby == false ? false : true
			def inStandby = devData?.on.toString() != "true" ? true : false
			atomicState?.inStandbyMode = inStandby
			def data = []
			atomicState?.selectedDevice.each { dev ->
				if (dni == dev?.key) {
					if(atomicState?.isWatering == true && schedData?.status != "PROCESSING") { handleWateringSched(false) }
					def newLabel = getDeviceLabelStr(devData?.name).toString()
					if(devLabel != newLabel) {
						d?.label = newLabel
						log.info "Device's Label has changed from (${devLabel}) to [${newLabel}]"
					}
					data = ["data":devData, "schedData":schedData, "rainDelay":rainDelay, "status": status, "standby":inStandby, "pauseInStandby":pauseInStandby]
				}
			}

			atomicState?.selectedZones.each { zone ->
				if (dni == zone?.key) {
					def zoneData = findZoneData(dni, devData)
					def newLabel = getDeviceLabelStr(zoneData?.name).toString()
					if(devLabel != newLabel) {
						d?.label = getDeviceLabelStr(zoneData?.name)
						log.info "Device's Label has changed from (${devLabel}) to [${newLabel}]"
					}
					data = ["data":zoneData, "schedData":schedData, "devId":atomicState?.deviceId, "status": status, "standby":inStandby, "pauseInStandby":pauseInStandby]
				}
			}
			if (d && data != []) {
				d.generateEvent(data)
			}
		} else { log.warn "pollChildren cannot update children because it is missing the required parameters..." }
	} catch(Exception ex) {
		log.error "exception polling children: ${ex}"
		//refreshAuthToken()
	}
	return result
}

def findZoneData(id, devData) {
	if(!id || !devData) { return null }
	if(devData?.zones) {
		//log.debug "devData: ${devData?.zones}"
		def res = devData?.zones.find { it?.id == id }
		//log.debug "res: $res"
		return res
	}
	return null
}

def setValue(child, newValue) {
	def jsonRequestBody = '{"value":'+ newValue+'}'
	def result = sendJson(child, jsonRequestBody)
	return result
}

def sendJson(subUri, jsonBody, standbyCmd=false) {
	//log.trace "Sending: ${jsonBody}"
	def returnStatus = false
	def cmdParams = [
		uri: "${apiEndpoint}/1/public/${subUri}",
		headers: ["Authorization": "Bearer ${atomicState?.authToken}", "Content-Type": "application/json"],
		body: jsonBody
	]

	try{
		if(!standbyCmd && settings?.pauseInStandby == true && atomicState?.inStandbyMode == true) {
			log.debug "Skipping this command while controller is in 'Standby Mode'..."
			return true
		}

		httpPut(cmdParams) { resp ->
			returnStatus = resp
			if(resp.status == 201 || resp.status == 204) {
				returnStatus = true
				//runIn(4, "poll", [overwrite: true])
			} else {
				//refresh the auth token
				if (resp.status == 401) {
					log.debug "Refreshing your authToken!"
					// refreshAuthToken()
				} else {
					log.error "Authentication error, invalid authentication method, lack of credentials, etc."
				}
			}

		}
	} catch(Exception e) {
		log.error "sendJson Exception Error: ${e}"
	}
	return returnStatus
}

//Section6: helper methods ---------------------------------------------------------------------------------------------

def toJson(Map m) {
	return new org.codehaus.groovy.grails.web.json.JSONObject(m).toString()
}

def toQueryString(Map m) {
	return m.collect { k, v -> "${k}=${URLEncoder.encode(v.toString())}" }.sort().join("&")
}

def epochToDt(val) {
	return formatDt(new Date(val))
}

def formatDt(dt) {
	def tf = new SimpleDateFormat("MMM d, yyyy - h:mm:ss a")
	if(location?.timeZone) { tf?.setTimeZone(location?.timeZone) }
	else {
		log.warn "SmartThings TimeZone is not found or is not set... Please Try to open your ST location and Press Save..."
		return null
	}
	return tf.format(dt)
}

def getDurationDesc(long secondsCnt) {
	int seconds = secondsCnt %60
	secondsCnt -= seconds
	long minutesCnt = secondsCnt / 60
	long minutes = minutesCnt % 60
	minutesCnt -= minutes
	long hoursCnt = minutesCnt / 60

	return "${minutes} min ${(seconds >= 0 && seconds < 10) ? "0${seconds}" : "${seconds}"} sec"
}

//Returns time differences is seconds
def GetTimeValDiff(timeVal) {
	try {
		def start = new Date(timeVal).getTime()
		def now = new Date().getTime()
		def diff = (int) (long) (now - start) / 1000
		//log.debug "diff: $diff"
		return diff
	} catch (ex) {
		log.error "GetTimeValDiff Exception: ${ex}"
		return 1000
	}
}

def getChildContName()	{ return "Rachio Controller" }
def getChildZoneName()	{ return "Rachio Zone" }
def getServerUrl()		{ return "https://graph.api.smartthings.com" }
def getShardUrl()		{ return getApiServerUrl() }
def getAppEndpoint()	{ return "https://app.rach.io" }
def getApiEndpoint()	{ return "https://api.rach.io" }
def getSupportUrl() 	{ return "http://support.rachio.com/" }
def getCommunityUrl() 	{ return "http://community.rachio.com/" }

def debugEventFromParent(child, message) {
	child.sendEvent("name":"debugEventFromParent", "value":message, "description":message, displayed: true, isStateChange: true)
}

//send both push notification and mobile activity feeds
def sendPushAndFeeds(notificationMessage){
	if (atomicState.timeSendPush){
		if (now() - atomicState.timeSendPush > 86400000){
			sendPush("Rachio " + notificationMessage)
			sendActivityFeeds(notificationMessage)
			atomicState.timeSendPush = now()
		}
	} else {
		sendPush("Rachio " + notificationMessage)
		sendActivityFeeds(notificationMessage)
		atomicState.timeSendPush = now()
	}
	atomicState.authToken = null
}

def sendActivityFeeds(notificationMessage) {
	def devices = app.getChildDevices(true)
	devices.each { child ->
		   //update(child)
		child.generateActivityFeedsEvent(notificationMessage)
	}
}

def standbyOn(child, deviceId) {
	log.debug "standbyOn() command received from ${child?.device?.displayName}"
	if(deviceId) {
		def jsonData = new JsonBuilder("id":deviceId)
		def res = sendJson("device/off", jsonData.toString(), true)
		// poll()
		child?.log("${child?.device.displayName} Standby OFF (Result: $res)")
		return res
	}
}

def standbyOff(child, deviceId) {
	log.debug "standbyOff() command received from ${child?.device?.displayName}"
	if(deviceId) {
		def jsonData = new JsonBuilder("id":deviceId)
		def res = sendJson("device/on", jsonData.toString(), true)
		// poll()
		child?.log("${child?.device.displayName} Standby OFF (Result: $res)")
		return res
	}
}

def on(child, deviceId) {
	log.trace "App on()..."
}

def off(child, deviceId) {
	log.trace "Received off() command from (${child?.device?.displayName})..."
	child?.log("Stop Watering - Received from (${child?.device.displayName})")
	if(deviceId) {
		def jsonData = new JsonBuilder("id":deviceId)
		def res = sendJson("device/stop_water", jsonData.toString())
		// poll()
		return res
	}
	return false
}

def setRainDelay(child, delayVal) {
	if (delayVal) {
		def secondsPerDay = 24*60*60;
		def duration = delayVal * secondsPerDay;
		def jsonData = new JsonBuilder("id":child?.device?.deviceNetworkId, "duration":duration)
		def res = sendJson("device/rain_delay", jsonData?.toString())

		if (res) { child?.sendEvent(name: 'rainDelay', value: delayVal) }
		return res
	}
	return false
}

def isWatering(devId) {
	//log.debug "isWatering()..."
	def res = _httpGet("device/${devId}/current_schedule");
	def result = (res && res?.status) ? true : false
	return result
}

void handleWateringSched(val=false) {
	if(val == true) {
		log.trace "Watering is Active... Scheduling poll for every 1 minute"
		runEvery1Minute("poll")
	} else {
		log.trace "Watering has finished... 1 minute Poll has been unscheduled"
		unschedule("poll")
		runIn(60, "poll") // This is here just to make sure that the schedule actually stopped and that the data is really current.
	}
	atomicState?.isWatering = val
}

def getDeviceStatus(devId) {
	return _httpGet("device/${devId}")
}

def getCurrentRainDelay(res) {
	//log.debug("getCurrentRainDelay($devId)...")
	// convert to configured rain delay to days.
	def ret =  (res?.rainDelayExpirationDate || res?.rainDelayStartDate) ? (res?.rainDelayExpirationDate - res?.rainDelayStartDate)/(26*60*60*1000) : 0
	def value = (long) Math.floor(ret + 0.5d)
	return value
}

def startZone(child, zoneNum, mins) {
	def res = false
	log.trace "Starting to water on (ZoneName: ${child?.device.displayName} | ZoneNumber: ${zoneNum} | RunDuration: ${mins}).."
	//child?.log("Starting to water on (ZoneName: ${child?.device.displayName} | ZoneNumber: ${zoneNum} | RunDuration: ${mins})...")
	def zoneId = child?.device?.deviceNetworkId
	if (zoneId && zoneNum && mins) {
		def duration = mins.toInteger() * 60
		def jsonData = new JsonBuilder("id":zoneId, "duration":duration)
		//log.debug "startZone jsonData: ${jsonData}"
		res = sendJson("zone/start", jsonData?.toString())
	} else { log.error "startZone Missing ZoneId or duration... ${zoneId} | ${mins}" }
	return res
}

def runAllZones(child, mins) {
	def res = false
	log.trace "runAllZones(ZoneName: ${child?.device?.displayName}, Duration: ${mins})..."
	//child?.log("runAllZones(ZoneName: ${child?.device?.displayName} | Duration: ${mins})")
	if (atomicState?.selectedZones && mins) {
		def zoneData = []
		def sortNum = 1
		def duration = mins.toInteger() * 60
		atomicState?.selectedZones?.each { z ->
			zoneData << ["id":z?.key, "duration":duration, "sortOrder": sortNum]
			sortNum = sortNum+1
		}
		def jsonData = new JsonBuilder("zones":zoneData)
		//child?.log("runAllZones  jsonData: ${jsonData}")
		res = sendJson("zone/start_multiple", jsonData?.toString())
	} else { log.error "runAllZones Missing ZoneIds or Duration... ${atomicState?.selectedZones} | ${mins}" }
	return res
}

def pauseScheduleRun(child) {
	log.trace "pauseScheduleRun..."
	def schedData = getCurSchedule(atomicState?.deviceId)
	def schedRuleData = getScheduleRuleInfo(schedData?.scheduleRuleId)
	child?.log "schedRuleData: $schedRuleData"
	child?.log "Schedule Started on: ${epochToDt(schedRuleData?.startDate)} | Total Duration: ${getDurationDesc(schedRuleData?.totalDuration.toLong())}"

	if(schedRuleData) {
		def zones = schedRuleData?.zones?.sort { a , b -> a.sortOrder <=> b.sortOrder }
		zones?.each { zn ->
			child?.log "Zone#: ${zn?.zoneNumber} | Zone Duration: ${getDurationDesc(zn?.duration.toLong())} | Order#: ${zn?.sortOrder}"
			if(zn?.zoneId == schedData?.zoneId) {
				def zoneRunTime = "Elapsed Runtime: ${getDurationDesc(GetTimeValDiff(schedData?.zoneStartDate.toLong()))}"
				child?.log "Zone Started: ${epochToDt(schedData?.zoneStartDate)} | ${zoneRunTime} | Cycle Count: ${schedData?.cycleCount} | Cycling: ${schedData?.cycling}"
			}
		}
	}
}

//Required by child devices
def getZones(device) {
	log.trace "getZones(${device.label})..."
	def res = _httpGet("device/${device?.deviceNetworkId}")
	return !res ? null : res?.zones
}

def getScheduleRuleInfo(schedId) {
	def res = _httpGet("schedulerule/${schedId}")
	return res
}

def restoreZoneSched(runData) {
	def res = false
	log.trace "restoreZoneSched( Data: ${runData})..."
	//child?.log("restoreZoneSched( Data: ${runData})...")
	if (atomicState?.selectedZones && mins) {
		def zoneData = []
		def sortNum = 1
		runData?.each { rd ->
			zoneData << ["id":rd?.zoneId, "duration": rd?.duration, "sortOrder": rd?.sortNumber]
		}
		def jsonData = new JsonBuilder("zones":zoneData)
		child?.log("restoreZoneSched jsonData: ${jsonData}")
		sendJson("zone/start_multiple", jsonData?.toString())
		res = true
	}
	return res
}
