#version 300 es
// モンスターの部品を描く。頂点の並びは MonsterModel.makeMesh と同じ

uniform mat4 u_Model;
uniform mat4 u_ModelViewProjection;

layout(location = 0) in vec4 a_Position;
layout(location = 1) in vec2 a_TexCoord;
layout(location = 2) in vec3 a_Normal;

out vec2 v_TexCoord;
out vec3 v_WorldNormal;

void main() {
  v_TexCoord = a_TexCoord;
  // 大きさの変化は向きに関係ないので、行列の回転の部分だけで法線を回す（長さはフラグメントで 1 に戻す）
  v_WorldNormal = mat3(u_Model) * a_Normal;
  gl_Position = u_ModelViewProjection * a_Position;
}
