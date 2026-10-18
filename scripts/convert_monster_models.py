# モンスターの 3D モデル（glTF）を、アプリで使う glb にまとめる
#
# 元のモデル：Quaternius「Ultimate Monsters」（CC0）の Big/glTF/*.gltf（iOS と同じモデル）
# glTF の中身（形・テクスチャ・14 種類のアニメーション）はそのままで、1 つのファイル（glb）にまとめるだけ
# アニメーションは名前（Idle、Punch など）で選んで流す（model/MonsterAnimation.kt）
#
#   python3 scripts/convert_monster_models.py "<Ultimate Monsters のフォルダ>/Big/glTF" app/src/main/assets/monsters
#
# 書き出した glb は `monster-<名前>.glb`（例：MushroomKing → monster-mushroom-king.glb）になる
# 名前は iOS の Monster.modelName と同じ。対戦相手のモンスターはこの名前で届く

import base64
import json
import os
import re
import struct
import sys

# iOS の Monster.all にある 16 体
NAMES = [
    "Alien", "Birb", "BlueDemon", "Bunny", "Cactoro", "Demon", "Dino", "Fish",
    "Frog", "Monkroose", "MushroomKing", "Ninja", "Orc", "Orc_Skull", "Tribal", "Yeti",
]


def model_name(name):
    """MushroomKing → monster-mushroom-king、Orc_Skull → monster-orc-skull"""
    words = re.sub(r"(?<!^)(?=[A-Z])", "-", name.replace("_", "")).lower()
    return "monster-" + words


def pack(src, dst):
    with open(src) as f:
        gltf = json.load(f)
    # 元のファイルはバッファを 1 つだけ持ち、中身を data URI で埋め込んでいる
    (buffer,) = gltf["buffers"]
    data = base64.b64decode(buffer["uri"].split(",", 1)[1])
    gltf["buffers"] = [{"byteLength": len(data)}]

    # glb のチャンクは 4 バイト単位にそろえる（JSON は空白で、バイナリは 0 で埋める）
    body = json.dumps(gltf, separators=(",", ":")).encode()
    body += b" " * (-len(body) % 4)
    data += b"\0" * (-len(data) % 4)
    length = 12 + 8 + len(body) + 8 + len(data)
    with open(dst, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, length))
        f.write(struct.pack("<II", len(body), 0x4E4F534A))
        f.write(body)
        f.write(struct.pack("<II", len(data), 0x004E4942))
        f.write(data)


src_dir, dst_dir = sys.argv[1:3]
os.makedirs(dst_dir, exist_ok=True)
for name in NAMES:
    dst = os.path.join(dst_dir, model_name(name) + ".glb")
    pack(os.path.join(src_dir, name + ".gltf"), dst)
    print(dst)
