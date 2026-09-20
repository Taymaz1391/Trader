#!/usr/bin/env python3
"""Generate a self-signed RSA key/cert for APK signing.

Outputs (in the target dir):
  key.pem          PKCS#8 private key (for scripts/sign-apk.mjs)
  cert.pem         self-signed X.509 certificate
  nobitrader.p12   PKCS#12 keystore (for Android build-tools apksigner)

The keystore password is "nobitrader-bot".
"""
import datetime
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import BestAvailableEncryption, pkcs12
from cryptography.x509.oid import NameOID

out = sys.argv[1] if len(sys.argv) > 1 else "keystore"
os.makedirs(out, exist_ok=True)

key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
name = x509.Name([
    x509.NameAttribute(NameOID.COMMON_NAME, "NobiTrader"),
    x509.NameAttribute(NameOID.ORGANIZATION_NAME, "NobiTrader Bot"),
])
now = datetime.datetime.now(datetime.timezone.utc)
cert = (
    x509.CertificateBuilder()
    .subject_name(name)
    .issuer_name(name)
    .public_key(key.public_key())
    .serial_number(x509.random_serial_number())
    .not_valid_before(now - datetime.timedelta(days=2))
    .not_valid_after(now + datetime.timedelta(days=11000))
    .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
    .sign(key, hashes.SHA256())
)

with open(os.path.join(out, "key.pem"), "wb") as f:
    f.write(key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ))
with open(os.path.join(out, "cert.pem"), "wb") as f:
    f.write(cert.public_bytes(serialization.Encoding.PEM))
p12 = pkcs12.serialize_key_and_certificates(
    name=b"nobitrader", key=key, cert=cert, cas=None,
    encryption_algorithm=BestAvailableEncryption(b"nobitrader-bot"),
)
with open(os.path.join(out, "nobitrader.p12"), "wb") as f:
    f.write(p12)

print("wrote", out, "=> key.pem, cert.pem, nobitrader.p12")
