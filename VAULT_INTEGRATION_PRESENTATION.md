# Vault Integration Architecture - PowerPoint Presentation

> **⚠️ 역사 기록 (2026-05-07).** 아래는 단일 키(`fernet-key`) 시절 발표자료의 산출물 목록이다.
> 슬라이드 내용(특히 "Fernet Key Structure", "Current Architecture")은 현행 KEK-DEK 구조와 다르다.
> 현행 아키텍처는 [VAULT_AND_ENCRYPTION.md](VAULT_AND_ENCRYPTION.md)와
> [VAULT_INTEGRATION_DIAGRAM.md](VAULT_INTEGRATION_DIAGRAM.md)를 참고하라.
> 발표자료를 다시 만들 경우 위 두 문서를 기준으로 갱신해야 한다.
> (이 목록에 있는 `.pptx`/생성 스크립트는 저장소에 커밋되어 있지 않다.)

## Overview
This document describes the PowerPoint presentation files for Vault integration.

## Files Created:

| File | Type | Description |
|------|------|-------------|
| Vault_Integration_Architecture.pptx | PowerPoint | Basic version (40KB) |
| Vault_Integration_Architecture_Enhanced.pptx | PowerPoint | Enhanced version (43KB) |
| generate_vault_ppt.py | Python Script | Basic PPTX generator |
| generate_vault_ppt_enhanced.py | Python Script | Enhanced PPTX generator |
| Vault_Integration_Architecture.html | HTML | Browser-based presentation |
| Vault_Integration_Architecture.rtf | RTF | PowerPoint importable |

## How to Use:

### Windows:
```bash
pip install python-pptx
python generate_vault_ppt_enhanced.py
# Output: Vault_Integration_Architecture_Enhanced.pptx
```

### Linux (with virtual env):
```bash
source my_env/bin/activate
python generate_vault_ppt_enhanced.py
# Output: Vault_Integration_Architecture_Enhanced.pptx
```

## Slide Contents (12 Slides):

1. **Title Slide**: Vault Integration Architecture
2. **Current Architecture**: Working state (2026-05-07)
3. **Vault kv-2 Structure**: Secret path details
4. **Fernet Key Structure**: 32 bytes (AES-128 + HMAC-SHA256)
5. **PasswordService Implementation**: VaultOperations.read() approach
6. **Encryption Flow**: Password → AES → Base64
7. **VaultResponse Structure**: kv-2 format
8. **Configuration Files**: application.properties
9. **Verification Results**: Test results (Post ID 2017587)
10. **Security Best Practices**: Recommendations
11. **Files Modified**: Repository changes
12. **Q&A**: Thank you!

## GitHub:
https://github.com/yadalhan/demoApp

## Date: 2026-05-07
