let customQDNHistoryPaths = []; // Array to track visited paths
let currentIndex = -1; // Index to track the current position in the history
let isManualNavigation = true; // Flag to control when to add new paths. set to false when navigating through a back/forward call


function resetVariables(){
let customQDNHistoryPaths = [];
let currentIndex = -1;
let isManualNavigation = true;
}

function getNameAfterService(url) {
    try {
        const parsedUrl = new URL(url);
        const pathParts = parsedUrl.pathname.split('/');

        // Find the index of "WEBSITE" or "APP" and get the next part
        const serviceIndex = pathParts.findIndex(part => part === 'WEBSITE' || part === 'APP');

        if (serviceIndex !== -1 && pathParts[serviceIndex + 1]) {
            return pathParts[serviceIndex + 1];
        } else {
            return null; // Return null if "WEBSITE" or "APP" is not found or has no following part
        }
    } catch (error) {
        console.error("Invalid URL provided:", error);
        return null;
    }
}



function parseUrl(url) {
    try {
        const parsedUrl = new URL(url);

        // Check if isManualNavigation query exists and is set to "false"
        const isManual = parsedUrl.searchParams.get("isManualNavigation");

        if (isManual !== null && isManual == "false") {
            isManualNavigation = false
            // Optional: handle this condition if needed (e.g., return or adjust the response)
        }


        // Remove theme, identifier, and time queries if they exist
        parsedUrl.searchParams.delete("theme");
        parsedUrl.searchParams.delete("lang");
        parsedUrl.searchParams.delete("themePalette");
        parsedUrl.searchParams.delete("identifier");
        parsedUrl.searchParams.delete("time");
        parsedUrl.searchParams.delete("isManualNavigation");
        // Extract the pathname and remove the prefix if it matches "render/APP" or "render/WEBSITE"
        const path = parsedUrl.pathname.replace(/^\/render\/(APP|WEBSITE)\/[^/]+/, "");

        // Combine the path with remaining query params (if any)
        return path + parsedUrl.search;
    } catch (error) {
        console.error("Invalid URL provided:", error);
        return null;
    }
}

// Tell the client to open a new tab. Done when an app is linking to another app
function openNewTab(data){
window.parent.postMessage({
action: 'SET_TAB',
requestedHandler:'UI',
payload: data
}, '*');
}
// sends navigation information to the client in order to manage back/forward navigation
function sendNavigationInfoToParent(isDOMContentLoaded){
window.parent.postMessage({
action: 'NAVIGATION_HISTORY',
requestedHandler:'UI',
payload: {
customQDNHistoryPaths,
currentIndex,
isDOMContentLoaded: isDOMContentLoaded ? true : false
}
}, '*');

}


function handleQDNResourceDisplayed(pathurl, isDOMContentLoaded) {
// make sure that an empty string the root path
if(pathurl?.startsWith('/render/hash/')) return;
const path = pathurl || '/'
    if (!isManualNavigation) {
    isManualNavigation = true
        // If the navigation is automatic (back/forward), do not add new entries
        return;
    }


    // If it's a new path, add it to the history array and adjust the index
    if (customQDNHistoryPaths[currentIndex] !== path) {

           customQDNHistoryPaths = customQDNHistoryPaths.slice(0, currentIndex + 1);



        // Add the new path and move the index to the new position
        customQDNHistoryPaths.push(path);
        currentIndex = customQDNHistoryPaths.length - 1;
            sendNavigationInfoToParent(isDOMContentLoaded)
    } else {
        currentIndex = customQDNHistoryPaths.length - 1
        sendNavigationInfoToParent(isDOMContentLoaded)
    }


    // Reset isManualNavigation after handling
    isManualNavigation = true;
}

function httpGet(url) {
    var request = new XMLHttpRequest();
    request.open("GET", url, false);
    request.send(null);
    return request.responseText;
}

function httpGetAsyncWithEvent(event, url) {
    fetch(url)
        .then((response) => response.text())
        .then((responseText) => {

            if (responseText == null) {
                // Pass to parent (UI), in case they can fulfil this request
                event.data.requestedHandler = "UI";
                parent.postMessage(event.data, '*', [event.ports[0]]);
                return;
            }

            handleResponse(event, responseText);

        })
        .catch((error) => {
            let res = {};
            res.error = error;
            handleResponse(event, JSON.stringify(res));
        })
}

function handleResponse(event, response) {
    if (event == null) {
        return;
    }

    // Handle empty or missing responses
    if (response == null || response.length == 0) {
        response = "{\"error\": \"Empty response\"}"
    }

    // Parse response
    let responseObj;
    try {
        responseObj = JSON.parse(response);
    } catch (e) {
        // Not all responses will be JSON
        responseObj = response;
    }

    // GET_QDN_RESOURCE_URL has custom handling
    const data = event.data;
    if (data.action == "GET_QDN_RESOURCE_URL") {
        if (responseObj == null || responseObj.status == null || responseObj.status == "NOT_PUBLISHED") {
            responseObj = {};
            responseObj.error = "Resource does not exist";
        }
        else {
            responseObj = buildResourceUrl(data.service, data.name, data.identifier, data.path, false);
        }
    }

    // Respond to app
    if (responseObj.error != null) {
        event.ports[0].postMessage({
            result: null,
            error: responseObj
        });
    }
    else {
        event.ports[0].postMessage({
            result: responseObj,
            error: null
        });
    }
}

function buildResourceUrl(service, name, identifier, path, isLink) {
    if (isLink == false) {
        // If this URL isn't being used as a link, then we need to fetch the data
        // synchronously, instead of showing the loading screen.
        url = "/arbitrary/" + service + "/" + name;
        if (identifier != null) url = url.concat("/" + identifier);
        if (path != null) url = url.concat("?filepath=" + path);
    }
    else if (_qdnContext == "render") {
        url = "/render/" + service + "/" + name;
        if (path != null) url = url.concat((path.startsWith("/") ? "" : "/") + path);
        if (identifier != null) url = url.concat("?identifier=" + identifier);
    }
    else if (_qdnContext == "gateway") {
        url = "/" + service + "/" + name;
        if (identifier != null) url = url.concat("/" + identifier);
        if (path != null) url = url.concat((path.startsWith("/") ? "" : "/") + path);
    }
    else {
        // domainMap only serves websites right now
        url = "/" + name;
        if (path != null) url = url.concat((path.startsWith("/") ? "" : "/") + path);
    }

    if (isLink) {
        const hasQuery = url.includes("?");
        const queryPrefix = hasQuery ? "&" : "?";
        url += queryPrefix + "theme=" + _qdnTheme + "&lang=" + _qdnLang;

        if (typeof _qdnThemePalette !== "undefined" && _qdnThemePalette !== null) {
            try {
                const serializedThemePalette = JSON.stringify(_qdnThemePalette);
                if (serializedThemePalette != null) {
                    url += "&themePalette=" + encodeURIComponent(serializedThemePalette);
                }
            } catch (error) {
                console.warn("Unable to serialize _qdnThemePalette:", error);
            }
        }
    }
    return url;
}

function extractComponents(url) {
    if (!url.startsWith("qortal://")) {
        return null;
    }

    url = url.replace(/^(qortal\:\/\/)/,"");
    if (url.includes("/")) {
        let parts = url.split("/");
        const service = parts[0].toUpperCase();
        parts.shift();
        const name = parts[0];
        parts.shift();
        let identifier;

        if (parts.length > 0) {
            identifier = parts[0]; // Do not shift yet
            // Check if a resource exists with this service, name and identifier combination
            const url = "/arbitrary/resource/status/" + service + "/" + name + "/" + identifier;
            const response = httpGet(url);
            const responseObj = JSON.parse(response);
            if (responseObj.totalChunkCount > 0) {
                // Identifier exists, so don't include it in the path
                parts.shift();
            }
            else {
                identifier = null;
            }
        }

        const path = parts.join("/");

        const components = {};
        components["service"] = service;
        components["name"] = name;
        components["identifier"] = identifier;
        components["path"] = path;
        return components;
    }

    return null;
}

function convertToResourceUrl(url, isLink) {
    if (!url.startsWith("qortal://")) {
        return null;
    }
    const c = extractComponents(url);
    if (c == null) {
        return null;
    }

    return buildResourceUrl(c.service, c.name, c.identifier, c.path, isLink);
}

window.addEventListener("message", async (event) => {
    if (event == null || event.data == null || event.data.length == 0) {
        return;
    }
    if (event.data.action == null) {
        // This could be a response from the UI
        handleResponse(event, event.data);
    }
    if (event.data.requestedHandler != null && event.data.requestedHandler === "UI") {
        // This request was destined for the UI, so ignore it
        return;
    }

    let url;
    let data = event.data;
    let identifier;
    switch (data.action) {
        case "GET_ACCOUNT_DATA":
            return httpGetAsyncWithEvent(event, "/addresses/" + data.address);

        case "GET_ACCOUNT_NAMES":
            return httpGetAsyncWithEvent(event, "/names/address/" + data.address);

        case "SEARCH_NAMES":
            url = "/names/search?";
            if (data.query != null) url = url.concat("&query=" + data.query);
            if (data.prefix != null) url = url.concat("&prefix=" + new Boolean(data.prefix).toString());
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            return httpGetAsyncWithEvent(event, url);

        case "GET_NAME_DATA":
            return httpGetAsyncWithEvent(event, "/names/" + data.name);

        case "GET_QDN_RESOURCE_URL":
            // Check status first; URL is built and returned automatically after status check
            url = "/arbitrary/resource/status/" + data.service + "/" + data.name;
            if (data.identifier != null) url = url.concat("/" + data.identifier);
            return httpGetAsyncWithEvent(event, url);

       case "LINK_TO_QDN_RESOURCE":
           if (data.service == null) data.service = "WEBSITE"; // Default to WEBSITE

           const nameOfCurrentApp = getNameAfterService(window.location.href);
           // Check to see if the link is an external app. If it is, request that the client opens a new tab instead of manipulating the window's history stack.
           if (nameOfCurrentApp !== data.name) {
               // Attempt to open a new tab and wait for a response
               const navigationPromise = new Promise((resolve, reject) => {
                   function handleMessage(event) {
                       if (event.data?.action === 'SET_TAB_SUCCESS' && event.data.payload?.name === data.name) {
                           window.removeEventListener('message', handleMessage);
                           resolve();
                       }
                   }

                   window.addEventListener('message', handleMessage);

                   // Send the message to the parent window
                   openNewTab({
                       name: data.name,
                       service: data.service,
                       identifier: data.identifier,
                       path: data.path
                   });

                   // Set a timeout to reject the promise if no response is received within 200ms
                   setTimeout(() => {
                       window.removeEventListener('message', handleMessage);
                       reject(new Error("No response within 200ms"));
                   }, 200);
               });

               // Handle the promise, and if it times out, fall back to the else block
               navigationPromise
                   .then(() => {
                       console.log('Tab opened successfully');
                   })
                   .catch(() => {
                       console.warn('No response, proceeding with window.location');
                       window.location = buildResourceUrl(data.service, data.name, data.identifier, data.path, true);
                   });
           } else {
               window.location = buildResourceUrl(data.service, data.name, data.identifier, data.path, true);
           }
           return;

        case "LIST_QDN_RESOURCES":
            url = "/arbitrary/resources?";
            if (data.service != null) url = url.concat("&service=" + data.service);
            if (data.name != null) url = url.concat("&name=" + data.name);
            if (data.identifier != null) url = url.concat("&identifier=" + data.identifier);
            if (data.default != null) url = url.concat("&default=" + new Boolean(data.default).toString());
            if (data.includeStatus != null) url = url.concat("&includestatus=" + new Boolean(data.includeStatus).toString());
            if (data.includeMetadata != null) url = url.concat("&includemetadata=" + new Boolean(data.includeMetadata).toString());
            if (data.nameListFilter != null) url = url.concat("&namefilter=" + data.nameListFilter);
            if (data.followedOnly != null) url = url.concat("&followedonly=" + new Boolean(data.followedOnly).toString());
            if (data.excludeBlocked != null) url = url.concat("&excludeblocked=" + new Boolean(data.excludeBlocked).toString());
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            return httpGetAsyncWithEvent(event, url);

        case "SEARCH_QDN_RESOURCES":
            url = "/arbitrary/resources/search?";
            if (data.service != null) url = url.concat("&service=" + data.service);
            if (data.query != null) url = url.concat("&query=" + data.query);
            if (data.identifier != null) url = url.concat("&identifier=" + data.identifier);
            if (data.name != null) url = url.concat("&name=" + data.name);
            if (data.names != null) data.names.forEach((x, i) => url = url.concat("&name=" + x));
            if (data.keywords != null) data.keywords.forEach((x, i) => url = url.concat("&keywords=" + x));
            if (data.title != null) url = url.concat("&title=" + data.title);
            if (data.description != null) url = url.concat("&description=" + data.description);
            if (data.prefix != null) url = url.concat("&prefix=" + new Boolean(data.prefix).toString());
            if (data.exactMatchNames != null) url = url.concat("&exactmatchnames=" + new Boolean(data.exactMatchNames).toString());
            if (data.default != null) url = url.concat("&default=" + new Boolean(data.default).toString());
            if (data.mode != null) url = url.concat("&mode=" + data.mode);
            if (data.minLevel != null) url = url.concat("&minlevel=" + data.minLevel);
            if (data.includeStatus != null) url = url.concat("&includestatus=" + new Boolean(data.includeStatus).toString());
            if (data.includeMetadata != null) url = url.concat("&includemetadata=" + new Boolean(data.includeMetadata).toString());
            if (data.nameListFilter != null) url = url.concat("&namefilter=" + data.nameListFilter);
            if (data.followedOnly != null) url = url.concat("&followedonly=" + new Boolean(data.followedOnly).toString());
            if (data.excludeBlocked != null) url = url.concat("&excludeblocked=" + new Boolean(data.excludeBlocked).toString());
            if (data.before != null) url = url.concat("&before=" + data.before);
            if (data.after != null) url = url.concat("&after=" + data.after);
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            return httpGetAsyncWithEvent(event, url);

        case "FETCH_QDN_RESOURCE":
            url = "/arbitrary/" + data.service + "/" + data.name;
            if (data.identifier != null) url = url.concat("/" + data.identifier);
            url = url.concat("?");
            if (data.filepath != null) url = url.concat("&filepath=" + data.filepath);
            if (data.rebuild != null) url = url.concat("&rebuild=" + new Boolean(data.rebuild).toString());
            if (data.encoding != null) url = url.concat("&encoding=" + data.encoding);
            return httpGetAsyncWithEvent(event, url);

        case "GET_QDN_RESOURCE_STATUS":
            url = "/arbitrary/resource/status/" + data.service + "/" + data.name;
            if (data.identifier != null) url = url.concat("/" + data.identifier);
            url = url.concat("?");
            if (data.build != null) url = url.concat("&build=" + new Boolean(data.build).toString());
            return httpGetAsyncWithEvent(event, url);

        case "GET_QDN_RESOURCE_PROPERTIES":
            identifier = (data.identifier != null) ? data.identifier : "default";
            url = "/arbitrary/resource/properties/" + data.service + "/" + data.name + "/" + identifier;
            return httpGetAsyncWithEvent(event, url);

        case "GET_QDN_RESOURCE_METADATA":
            identifier = (data.identifier != null) ? data.identifier : "default";
            url = "/arbitrary/metadata/" + data.service + "/" + data.name + "/" + identifier;
            return httpGetAsyncWithEvent(event, url);

        case "SEARCH_CHAT_MESSAGES":
            url = "/chat/messages?";
            if (data.before != null) url = url.concat("&before=" + data.before);
            if (data.after != null) url = url.concat("&after=" + data.after);
            if (data.txGroupId != null) url = url.concat("&txGroupId=" + data.txGroupId);
            if (data.involving != null) data.involving.forEach((x, i) => url = url.concat("&involving=" + x));
            if (data.reference != null) url = url.concat("&reference=" + data.reference);
            if (data.chatReference != null) url = url.concat("&chatreference=" + data.chatReference);
            if (data.hasChatReference != null) url = url.concat("&haschatreference=" + new Boolean(data.hasChatReference).toString());
            if (data.encoding != null) url = url.concat("&encoding=" + data.encoding);
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            return httpGetAsyncWithEvent(event, url);

        case "LIST_GROUPS":
            url = "/groups?";
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            return httpGetAsyncWithEvent(event, url);

        case "GET_BALANCE":
            url = "/addresses/balance/" + data.address;
            if (data.assetId != null) url = url.concat("&assetId=" + data.assetId);
            return httpGetAsyncWithEvent(event, url);

        case "GET_AT":
            url = "/at/" + data.atAddress;
            return httpGetAsyncWithEvent(event, url);

        case "GET_AT_DATA":
            url = "/at/" + data.atAddress + "/data";
            return httpGetAsyncWithEvent(event, url);

        case "LIST_ATS":
            url = "/at/byfunction/" + data.codeHash58 + "?";
            if (data.isExecutable != null) url = url.concat("&isExecutable=" + data.isExecutable);
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            return httpGetAsyncWithEvent(event, url);

        case "FETCH_BLOCK":
            if (data.signature != null) {
                url = "/blocks/signature/" + data.signature;
            } else if (data.height != null) {
                url = "/blocks/byheight/" + data.height;
            }
            url = url.concat("?");
            if (data.includeOnlineSignatures != null) url = url.concat("&includeOnlineSignatures=" + data.includeOnlineSignatures);
            return httpGetAsyncWithEvent(event, url);

        case "FETCH_BLOCK_RANGE":
            url = "/blocks/range/" + data.height + "?";
            if (data.count != null) url = url.concat("&count=" + data.count);
            if (data.reverse != null) url = url.concat("&reverse=" + data.reverse);
            if (data.includeOnlineSignatures != null) url = url.concat("&includeOnlineSignatures=" + data.includeOnlineSignatures);
            return httpGetAsyncWithEvent(event, url);

        case "SEARCH_TRANSACTIONS":
            url = "/transactions/search?";
            if (data.startBlock != null) url = url.concat("&startBlock=" + data.startBlock);
            if (data.blockLimit != null) url = url.concat("&blockLimit=" + data.blockLimit);
            if (data.txGroupId != null) url = url.concat("&txGroupId=" + data.txGroupId);
            if (data.txType != null) data.txType.forEach((x, i) => url = url.concat("&txType=" + x));
            if (data.address != null) url = url.concat("&address=" + data.address);
            if (data.confirmationStatus != null) url = url.concat("&confirmationStatus=" + data.confirmationStatus);
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            return httpGetAsyncWithEvent(event, url);

        case "GET_PRICE":
            url = "/crosschain/price/" + data.blockchain + "?";
            if (data.maxtrades != null) url = url.concat("&maxtrades=" + data.maxtrades);
            if (data.inverse != null) url = url.concat("&inverse=" + data.inverse);
            return httpGetAsyncWithEvent(event, url);


         case "PERFORMING_NON_MANUAL":
                    isManualNavigation = false
                    currentIndex = data.currentIndex
                    return;

        default:
            // Pass to parent (UI), in case they can fulfil this request
            event.data.requestedHandler = "UI";
            parent.postMessage(event.data, '*', [event.ports[0]]);


            return;
    }

}, false);


/**
 * Listen for and intercept all link click events
 */
function interceptClickEvent(e) {
    var target = e.target || e.srcElement;
    if (target.tagName !== 'A') {
        target = target.closest('A');
    }
    if (target == null || target.getAttribute('href') == null) {
        return;
    }
    let href = target.getAttribute('href');
    if (href.startsWith("qortal://")) {
        const c = extractComponents(href);
        if (c != null) {
            qortalRequest({
                action: "LINK_TO_QDN_RESOURCE",
                service: c.service,
                name: c.name,
                identifier: c.identifier,
                path: c.path
            });
        }
        e.preventDefault();
    }
    else if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("//")) {
        // Block external links
        e.preventDefault();
    }
}
if (document.addEventListener) {
    document.addEventListener('click', interceptClickEvent);
}
else if (document.attachEvent) {
    document.attachEvent('onclick', interceptClickEvent);
}



/**
 * Intercept image loads from the DOM
 */
document.addEventListener('DOMContentLoaded', () => {
    const imgElements = document.querySelectorAll('img');
    imgElements.forEach((img) => {
        let url = img.src;
        const newUrl = convertToResourceUrl(url, false);
        if (newUrl != null) {
            document.querySelector('img').src = newUrl;
        }
    });
});

/**
 * Intercept img src updates
 */
document.addEventListener('DOMContentLoaded', () => {
    const imgElements = document.querySelectorAll('img');
    imgElements.forEach((img) => {
        let observer = new MutationObserver((changes) => {
            changes.forEach(change => {
                if (change.attributeName.includes('src')) {
                    const newUrl = convertToResourceUrl(img.src, false);
                    if (newUrl != null) {
                        document.querySelector('img').src = newUrl;
                    }
                }
            });
        });
        observer.observe(img, {attributes: true});
    });
});



const awaitTimeout = (timeout, reason) =>
    new Promise((resolve, reject) =>
        setTimeout(
            () => (reason === undefined ? resolve() : reject(reason)),
            timeout
        )
    );

function getDefaultTimeout(action) {
    if (action != null) {
        // Some actions need longer default timeouts, especially those that create transactions
        switch (action) {
            case "GET_USER_ACCOUNT":
            case "SAVE_FILE":
            case "SIGN_TRANSACTION":
            case "DECRYPT_DATA":
                // User may take a long time to accept/deny the popup
                return 60 * 60 * 1000;

            case "SEARCH_QDN_RESOURCES":
                // Searching for data can be slow, especially when metadata and statuses are also being included
                return 30 * 1000;

            case "FETCH_QDN_RESOURCE":
                // Fetching data can take a while, especially if the status hasn't been checked first
                return 60 * 1000;

            case "PUBLISH_QDN_RESOURCE":
            case "PUBLISH_MULTIPLE_QDN_RESOURCES":
                // Publishing could take a very long time on slow system, due to the proof-of-work computation
                return 60 * 60 * 1000;

            case "SEND_CHAT_MESSAGE":
                // Chat messages rely on PoW computations, so allow extra time
                return 60 * 1000;

            case "CREATE_TRADE_BUY_ORDER":
            case "CREATE_TRADE_SELL_ORDER":
            case "CANCEL_TRADE_SELL_ORDER":
            case "VOTE_ON_POLL":
            case "CREATE_POLL":
            case "JOIN_GROUP":
            case "DEPLOY_AT":
            case "SEND_COIN":
                // Allow extra time for other actions that create transactions, even if there is no PoW
                return 5 * 60 * 1000;

            case "GET_WALLET_BALANCE":
                // Getting a wallet balance can take a while, if there are many transactions
                return 2 * 60 * 1000;

            default:
                break;
        }
    }
    return 30 * 1000;
}

/**
 * Make a Qortal (Q-Apps) request with no timeout
 */
const qortalRequestWithNoTimeout = (request) => new Promise((res, rej) => {
    const channel = new MessageChannel();

    channel.port1.onmessage = ({data}) => {
        channel.port1.close();

        if (data.error) {
            rej(data.error);
        } else {
            res(data.result);
        }
    };

    window.postMessage(request, '*', [channel.port2]);
});

/**
 * Make a Qortal (Q-Apps) request with the default timeout (10 seconds)
 */
const qortalRequest = (request) =>
    Promise.race([qortalRequestWithNoTimeout(request), awaitTimeout(getDefaultTimeout(request.action), "The request timed out")]);

/**
 * Make a Qortal (Q-Apps) request with a custom timeout, specified in milliseconds
 */
const qortalRequestWithTimeout = (request, timeout) =>
    Promise.race([qortalRequestWithNoTimeout(request), awaitTimeout(timeout, "The request timed out")]);


/**
 * Send current page details to UI
 */
document.addEventListener('DOMContentLoaded', (event) => {
 
resetVariables()
    qortalRequest({
        action: "QDN_RESOURCE_DISPLAYED",
        service: _qdnService,
        name: _qdnName,
        identifier: _qdnIdentifier,
        path: _qdnPath
    });
    // send to the client the first path when the app loads.
    const firstPath = parseUrl(window?.location?.href || "")
    handleQDNResourceDisplayed(firstPath, true);
  // Increment counter when page fully loads
});

/**
 * Handle app navigation
 */
navigation.addEventListener('navigate', (event) => {

    const url = new URL(event.destination.url);

    let fullpath = url.pathname + url.hash;
    const processedPath = (fullpath.startsWith(_qdnBase)) ? fullpath.slice(_qdnBase.length) : fullpath;
    qortalRequest({
        action: "QDN_RESOURCE_DISPLAYED",
        service: _qdnService,
        name: _qdnName,
        identifier: _qdnIdentifier,
        path: processedPath
    });

   // Put a timeout so that the DOMContentLoaded listener's logic executes before the navigate listener
   setTimeout(()=> {
    handleQDNResourceDisplayed(processedPath);
   }, 100)
});
