console.log("Gateway mode");

const gatewayInteractiveActions = new Set([
  "GET_USER_ACCOUNT",
  "SAVE_FILE",
  "SIGN_TRANSACTION",
  "DECRYPT_DATA",
  "PUBLISH_QDN_RESOURCE",
  "PUBLISH_MULTIPLE_QDN_RESOURCES",
  "SEND_CHAT_MESSAGE",
  "CREATE_TRADE_BUY_ORDER",
  "CREATE_TRADE_SELL_ORDER",
  "CANCEL_TRADE_SELL_ORDER",
  "VOTE_ON_POLL",
  "CREATE_POLL",
  "JOIN_GROUP",
  "DEPLOY_AT",
  "GET_WALLET_BALANCE",
  "SEND_COIN",
  "GET_LIST_ITEMS",
  "ADD_LIST_ITEMS",
  "DELETE_LIST_ITEM",
]);

const gatewayLocalActions = new Set(["QDN_RESOURCE_DISPLAYED"]);

function sendRequestToExtension(
  requestType,
  payload,
  timeout = 750
) {
  return new Promise((resolve, reject) => {
    const requestId = Math.random().toString(36).substring(2, 15); // Generate a unique ID for the request
    const detail = {
      type: requestType,
      payload,
      requestId,
      timeout: timeout / 1000,
    };

    // Store the timeout ID so it can be cleared later
    const timeoutId = setTimeout(() => {
      document.removeEventListener("qortalExtensionResponses", handleResponse);
      reject(new Error("Request timed out"));
    }, timeout); // Adjust timeout as necessary

    function handleResponse(event) {
      const { requestId: responseId, data } = event.detail;
      if (requestId === responseId) {
        // Match the response with the request
        document.removeEventListener("qortalExtensionResponses", handleResponse);
        clearTimeout(timeoutId); // Clear the timeout upon successful response
        resolve(data);
      }
    }

    document.addEventListener("qortalExtensionResponses", handleResponse);
    document.dispatchEvent(
      new CustomEvent("qortalExtensionRequests", { detail })
    );
  });
}

 const isExtensionInstalledFunc = async () => {
  try {
    const response = await sendRequestToExtension(
      "REQUEST_IS_INSTALLED",
      {},
      750
    );
    return response;
  } catch (error) {
    // not installed
  }
};



function qdnGatewayShowModal(message) {
    const modalElementId = "qdnGatewayModal";

    if (document.getElementById(modalElementId) != null) {
        document.body.removeChild(document.getElementById(modalElementId));
    }

    var modalElement = document.createElement('div');
    modalElement.style.cssText = 'position:fixed; z-index:99999; background:#fff; padding:20px; border-radius:5px; font-family:sans-serif; bottom:20px; right:20px; color:#000; max-width:400px; box-shadow:0 3px 10px rgb(0 0 0 / 0.2); font-family:arial; font-weight:normal; font-size:16px;';
    modalElement.innerHTML = message + "<br /><br />";
    modalElement.id = modalElementId;

    var closeButton = document.createElement('button');
    closeButton.style.cssText = 'background-color:#008CBA; border:none; color:white; cursor:pointer; float: right; margin: 10px; padding:15px; border-radius:5px; display:inline-block; text-align:center; text-decoration:none; font-family:arial; font-weight:normal; font-size:16px;';
    closeButton.innerText = "Close";
    closeButton.addEventListener ("click", function() {
        document.body.removeChild(document.getElementById(modalElementId));
    });
    modalElement.appendChild(closeButton);

    var qortalButton = document.createElement('button');
    qortalButton.style.cssText = 'background-color:#4CAF50; border:none; color:white; cursor:pointer; float: right; margin: 10px; padding:15px; border-radius:5px; text-align:center; text-decoration:none; display:inline-block; font-family:arial; font-weight:normal; font-size:16px;';
    qortalButton.innerText = "Learn more";
    qortalButton.addEventListener ("click", function() {
        document.body.removeChild(document.getElementById(modalElementId));
        window.open("https://qortal.dev");
    });
    modalElement.appendChild(qortalButton);

    document.body.appendChild(modalElement);
}

window.addEventListener("message", async (event) => {
    if (event == null || event.data == null || event.data.length == 0) {
        return;
    }
    if (event.data.action == null) {
        return;
    }

    if (gatewayLocalActions.has(event.data.action)) {
        // Gateway pages do not have a Qortal UI to receive navigation
        // tracking messages. Acknowledge these locally so the app's
        // qortalRequest() promise does not time out during page load.
        event.stopImmediatePropagation();
        const port = event.ports?.[0];
        if (port != null) {
            port.postMessage({ result: null, error: null });
        }
        return;
    }

    // Requests without a handler are the original qortalRequest() messages.
    // Handle them here before q-apps.js can transfer their MessagePort to the
    // parent. A request already marked for the UI has already been forwarded.
    if (event.data.requestedHandler === "UI") {
        return;
    }

    if (!gatewayInteractiveActions.has(event.data.action)) {
        return;
    }

    // q-apps.js also listens for message events. This handler must claim the
    // request synchronously because the extension check below is asynchronous.
    event.stopImmediatePropagation();
    
    let response;
    let data = event.data;

    switch (data.action) {
        case "GET_USER_ACCOUNT":
        case "SAVE_FILE":
        case "SIGN_TRANSACTION":
        case "DECRYPT_DATA":
        case "PUBLISH_QDN_RESOURCE":
        case "PUBLISH_MULTIPLE_QDN_RESOURCES":
        case "SEND_CHAT_MESSAGE":
        case "CREATE_TRADE_BUY_ORDER":
        case "CREATE_TRADE_SELL_ORDER":
        case "CANCEL_TRADE_SELL_ORDER":
        case "VOTE_ON_POLL":
        case "CREATE_POLL":
        case "JOIN_GROUP":
        case "DEPLOY_AT":
        case "GET_WALLET_BALANCE":
        case "SEND_COIN":
        case "GET_LIST_ITEMS":
        case "ADD_LIST_ITEMS":
        case "DELETE_LIST_ITEM":
            const isExtInstalledRes = await isExtensionInstalledFunc();
            if (isExtInstalledRes?.version) {
                // Preserve the normal UI flow when the extension is present.
                // The forwarded event is marked so this gateway handler does
                // not process it again if parent === window.
                event.data.requestedHandler = "UI";
                const port = event.ports?.[0];
                if (port != null) {
                    parent.postMessage(event.data, "*", [port]);
                } else {
                    parent.postMessage(event.data, "*");
                }
                return;
            }
            const errorString = "Interactive features were requested, but these are not yet supported when viewing via a gateway. To use interactive features, please access using the Qortal Hub on a desktop. More info at: https://qortal.dev/onboarding";
            response = "{\"error\": \"" + errorString + "\"}";

            const modalText = "This app is powered by the Qortal blockchain. You are viewing in read-only mode. To use interactive features, please access using the Qortal Hub on a desktop. More info at: https://qortal.dev/onboarding";
            qdnGatewayShowModal(modalText);
            break;

        default:
            console.log('Unhandled gateway message: ' + JSON.stringify(data));
            return;
    }

    handleResponse(event, response);

}, false);
