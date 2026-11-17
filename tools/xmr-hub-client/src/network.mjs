/** The owner chose Monero stagenet for Qortal testnet, mainnet otherwise. */
export function networkForNodeInfo(nodeInfo) {
  if (!nodeInfo || typeof nodeInfo.isTestNet !== 'boolean') {
    throw new Error('Qortal node mode is unavailable');
  }
  return nodeInfo.isTestNet ? 'stagenet' : 'mainnet';
}

export function assertDaemonNetwork(expected, daemonInfo) {
  if (!['mainnet', 'stagenet'].includes(expected)) {
    throw new Error('Unsupported application network');
  }
  if (!daemonInfo || daemonInfo.nettype !== expected) {
    throw new Error('Monero daemon network does not match Qortal mode');
  }
}

/** Resolve assets beneath the QDN app root instead of the node's root path. */
export function workerUrl(appRoot, documentUrl) {
  const document = new URL(documentUrl);
  const root = new URL(appRoot || './', document);
  if (root.origin !== document.origin || root.search || root.hash || !root.pathname.endsWith('/')) {
    throw new Error('Expected a same-origin application directory');
  }
  return new URL('monero.worker.js', root).href;
}
