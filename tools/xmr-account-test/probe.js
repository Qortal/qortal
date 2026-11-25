(() => {
  const fields = ['address', 'derivationVersion', 'network', 'publicSpendKey', 'publicViewKey'];
  const button = document.getElementById('request');
  const status = document.getElementById('status');
  const output = document.getElementById('result');
  const compare = document.getElementById('compare');
  let current;

  button.addEventListener('click', async () => {
    current = undefined;
    compare.disabled = true;
    output.textContent = 'No account requested.';
    document.getElementById('comparison').textContent = '';
    if (typeof qortalRequest !== 'function') {
      status.textContent = 'Open this page through Hub Development Mode; the Q-App bridge is unavailable here.';
      return;
    }
    button.disabled = true;
    status.textContent = 'Waiting for Hub wallet permission…';
    try {
      const response = await qortalRequest({ action: 'GET_USER_WALLET', coin: 'XMR' });
      if (!response || typeof response !== 'object'
        || Object.keys(response).sort().join(',') !== fields.join(',')
        || response.derivationVersion !== 1
        || !['mainnet', 'stagenet'].includes(response.network)
        || !/^[1-9A-HJ-NP-Za-km-z]{95}$/.test(response.address)
        || !/^[0-9a-f]{64}$/.test(response.publicSpendKey)
        || !/^[0-9a-f]{64}$/.test(response.publicViewKey)) {
        status.textContent = 'Unexpected wallet response. Nothing from that response has been displayed. Check that Hub is on xmr-support and unlock the account again.';
        return;
      }
      current = Object.fromEntries(fields.map(field => [field, response[field]]));
      output.textContent = JSON.stringify(current, null, 2);
      status.textContent = `PASS: public ${current.network} account, derivation version 1.`;
      compare.disabled = false;
    } catch {
      status.textContent = 'Request failed or was declined. Check Hub permission, account unlock and the selected Core mode.';
    } finally {
      button.disabled = false;
    }
  });

  compare.addEventListener('click', () => {
    const expected = document.getElementById('expected').value.trim();
    document.getElementById('comparison').textContent = !expected
      ? 'Paste the previously recorded public address first.'
      : current?.address === expected
        ? 'PASS: the recovered public address matches.'
        : 'Addresses differ. Check that the account and network match the earlier test.';
  });
})();
