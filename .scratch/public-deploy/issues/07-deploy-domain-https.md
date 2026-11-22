# 07: VM 배포와 도메인·HTTPS 연결

**What to build:** Bootstrap the VM, create its `.env`, deploy the production stack, and once the operator adds the Cloudflare A record `live` → VM IP (DNS only), `https://live.codecodecode333.dev` serves the whole service with a Let's Encrypt certificate. Check from outside: DNS, the certificate, HTTP→HTTPS, `/healthz`, signup/login, VOD upload/playback, 방송 list, 댓글. README gains the public deployment section: domain registered, first registration "$8.20 (카드 결제 11,287원)", renewal $12.20/yr, public address, DNS and TLS setup, VM shape and why, how to deploy. See spec and ADR-0006.

**Blocked by:** 02, 06, operator adds the DNS record

**Status:** ready-for-agent

- [ ] Stack running on the VM and surviving a reboot
- [ ] External checks above pass and are recorded (command output)
- [ ] README public deployment section as described
