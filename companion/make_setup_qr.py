"""Offline setup QR: python make_setup_qr.py. No server or network calls."""
import getpass
import html
import io
import json
import os
import re
from pathlib import Path


def payload(provider, model, key):
    if provider not in ('openai', 'gemini', 'openrouter'):
        raise ValueError('Provider must be openai, gemini or openrouter')
    valid_model = (model == 'openrouter/free' or re.fullmatch(
        r'[A-Za-z0-9][A-Za-z0-9_-]{0,59}/[A-Za-z0-9][A-Za-z0-9._-]{0,119}:free', model)) if provider == 'openrouter' else re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,119}', model)
    if not valid_model:
        raise ValueError('Invalid model ID')
    if not 16 <= len(key) <= 512 or any(not 33 <= ord(c) <= 126 for c in key):
        raise ValueError('Invalid API key format')
    return json.dumps({'type': 'computer-noob-helper.config', 'version': 1,
                       'provider': provider, 'model': model, 'api_key': key}, separators=(',', ':'))


def create_document(provider, model, key):
    import qrcode
    from qrcode.image.svg import SvgPathImage
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=10, border=4)
    qr.add_data(payload(provider, model, key)); qr.make(fit=True)
    output = io.BytesIO()
    qr.make_image(image_factory=SvgPathImage).save(output)
    svg = output.getvalue().decode().replace('<?xml version=\'1.0\' encoding=\'UTF-8\'?>', '')
    return '''<!doctype html><html lang="ko"><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>컴맹도우미 설정 QR</title><style>
body{font:18px system-ui;text-align:center;margin:24px;color:#17211c;background:white}
svg{display:block;margin:16px auto;width:min(80vw,620px);height:auto}
p{max-width:640px;margin:16px auto;line-height:1.6}
</style><h1>컴맹도우미 설정</h1><p>''' + html.escape(provider + ' · ' + model) + '''</p>''' + svg + '''
<p>안경 앱 → 메뉴 → Provider 설정 → 설정 QR 읽기</p>
<p><strong>이 QR에는 API 키가 들어 있습니다.</strong> 다른 사람에게 공유하지 마세요.
읽기가 끝나면 이 창을 닫고 파일을 삭제한 뒤 안경 카메라를 다시 연결하세요.</p>
<p>이 페이지는 외부 요청이나 스크립트를 실행하지 않습니다.</p></html>'''


def main():
    provider = input('Provider (openai / gemini / openrouter): ').strip().lower()
    default_model = 'google/gemma-4-26b-a4b-it:free' if provider == 'openrouter' else ''
    model = input('이미지 입력을 지원하는 모델 ID' + (f' [{default_model}]' if default_model else '') + ': ').strip() or default_model
    key = getpass.getpass('API 키 (화면에 표시되지 않음): ').strip()
    document = create_document(provider, model, key)
    path = Path('setup-qr.html')
    # No accidental overwrite or following symlinks. File contains credentials.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as output:
        output.write(document)
    print('setup-qr.html을 브라우저에서 여세요. 등록 후 파일을 삭제하세요.')


if __name__ == '__main__':
    main()
