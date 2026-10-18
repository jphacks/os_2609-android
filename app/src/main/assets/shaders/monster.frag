#version 300 es
// モンスターの部品を、テクスチャの色 ×（環境光 + 上から当たる光）× 部屋の明るさ で塗る
precision mediump float;

uniform sampler2D u_Texture;
// ARCore が推定した部屋の色味（rgb）と明るさ（a）。CaptureRenderer が毎フレーム渡す
uniform vec4 u_ColorCorrection;

in vec2 v_TexCoord;
in vec3 v_WorldNormal;

layout(location = 0) out vec4 o_FragColor;

// 斜め上から当たる光の向き（ワールド座標、光に向かう向き）
const vec3 kLightDirection = vec3(0.3, 1.0, 0.5);
// 光が当たらない面の明るさ。暗すぎると AR の中で黒くつぶれて見える
const float kAmbient = 0.55;
const float kDiffuse = 0.6;
// ARCore の明るさの推定で、ふつうの部屋の明るさにあたる値（ARCore のサンプルと同じ）
const float kMiddleGrayIntensity = 0.466;

void main() {
  vec3 albedo = texture(u_Texture, v_TexCoord).rgb;
  float diffuse = max(dot(normalize(v_WorldNormal), normalize(kLightDirection)), 0.0);
  vec3 color = albedo * (kAmbient + kDiffuse * diffuse);
  color *= u_ColorCorrection.rgb * (u_ColorCorrection.a / kMiddleGrayIntensity);
  o_FragColor = vec4(color, 1.0);
}
