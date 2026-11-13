#!/usr/bin/env bash

set -e

# Assumes Let's Encrypt

if [ $# -ne 1 -a $# -ne 3 ]; then
	echo "usage: ${0%%*/} <domain> [<keystore> <password>]"
	exit 2
fi

domain=$1
keystore=${2:-core-api.keystore}
pass=${3:-kspassword}

LEdirs=(/usr/local/etc /etc /opt .)
for LEdir in "${LEdirs[@]}"; do
	srcdir="${LEdir}/letsencrypt/live/${domain}"
	if [ -d "$srcdir" ]; then
		echo "Using certs & keys from ${srcdir}"
		break;
	fi
	unset srcdir
done

if [ -z "${srcdir}" ]; then
	echo "Can't find Let's Encrypt folder for ${domain}"
	exit 1
fi

# key & cert
# Keep the existing keystore until both conversions succeed. Temporary key
# material is private and removed on failure or success.
umask 077
keystore_dir=$(dirname -- "$keystore")
staging=$(mktemp -d "$keystore_dir/.qortal-keystore.XXXXXX")
trap 'rm -rf -- "$staging"' EXIT
openssl pkcs12 \
	-inkey "${srcdir}/privkey.pem" -in "${srcdir}/fullchain.pem" \
	-export -out "$staging/cert.p12" -passout pass:"${pass}" \
	-name "${domain}"

keytool -importkeystore -noprompt \
	-srckeystore "$staging/cert.p12" -srcstoretype PKCS12 -srcstorepass "${pass}" \
	-destkeystore "$staging/keystore" -deststorepass "${pass}" -destkeypass "${pass}" \
	-alias "${domain}"
mv -f -- "$staging/keystore" "$keystore"

printf 'Built keystore: %s, with password: %s\nFor settings.json:\n' "$keystore" "$pass"

printf "\tsslKeystorePathname: \"%s\",\n" "${keystore}"
printf "\tsslKeystorePassword: \"%s\",\n" "${pass}"
