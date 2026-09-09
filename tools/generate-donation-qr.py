"""Generate and round-trip verify the donation QR. Requires qrcode, Pillow, zxing-cpp."""
from pathlib import Path
import qrcode
import zxingcpp

URL = 'https://donate.stream/donate_6a60559cd9e35'
destination = Path(__file__).resolve().parents[1] / 'docs/assets/donate-qr.png'
destination.parent.mkdir(parents=True, exist_ok=True)
qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=8, border=4)
qr.add_data(URL)
qr.make(fit=True)
picture = qr.make_image(fill_color='black', back_color='white').convert('RGB')
decoded = zxingcpp.read_barcode(picture)
assert decoded and decoded.text == URL, 'Donation QR round-trip failed'
picture.save(destination)
print(f'QR verified: {URL}')
