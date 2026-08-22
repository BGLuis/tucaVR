//! Álgebra de quaternions e vetores 3D para rotação espacial de áudio — PHASE-0.3, Seções 3 e 4.
//!
//! Fornece estruturas leves e sem alocação dinâmica para:
//! 1. Representação de pontos/caixas no espaço 3D (`Vec3`).
//! 2. Rotação tridimensional por quaternion de rastreamento de cabeça (`Quat`).
//! 3. Conversão para coordenadas esféricas (azimute e elevação para busca em HRTF).
//! 4. Interpolação esférica (Slerp) e linear para suavização de saltos de pose entre blocos de áudio.

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Vec3 {
    pub x: f32,
    pub y: f32,
    pub z: f32,
}

impl Vec3 {
    #[inline]
    pub const fn new(x: f32, y: f32, z: f32) -> Self {
        Self { x, y, z }
    }

    pub const ZERO: Self = Self::new(0.0, 0.0, 0.0);
    pub const FORWARD: Self = Self::new(0.0, 0.0, -1.0); // Convenção OpenGL/OpenXR: -Z para frente
    pub const RIGHT: Self = Self::new(1.0, 0.0, 0.0);
    pub const UP: Self = Self::new(0.0, 1.0, 0.0);

    #[inline]
    pub fn length_squared(self) -> f32 {
        self.x * self.x + self.y * self.y + self.z * self.z
    }

    #[inline]
    pub fn length(self) -> f32 {
        self.length_squared().sqrt()
    }

    #[inline]
    pub fn normalize(self) -> Self {
        let len = self.length();
        if len > 1e-6 {
            Self {
                x: self.x / len,
                y: self.y / len,
                z: self.z / len,
            }
        } else {
            Self::FORWARD
        }
    }

    #[inline]
    pub fn dot(self, other: Self) -> f32 {
        self.x * other.x + self.y * other.y + self.z * other.z
    }

    /// Cria um vetor a partir de azimute (graus, horizontal: 0° = frente, +90° = direita, -90° = esquerda)
    /// e elevação (graus, vertical: +90° = cima, -90° = baixo).
    pub fn from_spherical_degrees(azimuth_deg: f32, elevation_deg: f32, radius: f32) -> Self {
        let az_rad = azimuth_deg.to_radians();
        let el_rad = elevation_deg.to_radians();
        let cos_el = el_rad.cos();
        Self {
            x: radius * az_rad.sin() * cos_el,
            y: radius * el_rad.sin(),
            z: -radius * az_rad.cos() * cos_el,
        }
    }

    /// Converte este vetor em azimute e elevação em graus.
    /// Retorna `(azimuth_deg, elevation_deg)` onde:
    /// - Azimute está em `[-180.0, 180.0]` (0° = frente, +90° = direita, -90° = esquerda, ±180° = trás).
    /// - Elevação está em `[-90.0, 90.0]` (0° = horizontal, +90° = topo, -90° = baixo).
    pub fn to_spherical_degrees(self) -> (f32, f32) {
        let len = self.length();
        if len < 1e-6 {
            return (0.0, 0.0);
        }
        let elevation_deg = (self.y / len).clamp(-1.0, 1.0).asin().to_degrees();
        // Em -Z como frente: x = sin(az), -z = cos(az) -> atan2(x, -z)
        let azimuth_deg = self.x.atan2(-self.z).to_degrees();
        (azimuth_deg, elevation_deg)
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Quat {
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub w: f32,
}

impl Quat {
    pub const IDENTITY: Self = Self {
        x: 0.0,
        y: 0.0,
        z: 0.0,
        w: 1.0,
    };

    #[inline]
    pub const fn new(x: f32, y: f32, z: f32, w: f32) -> Self {
        Self { x, y, z, w }
    }

    #[inline]
    pub fn conjugate(self) -> Self {
        Self {
            x: -self.x,
            y: -self.y,
            z: -self.z,
            w: self.w,
        }
    }

    #[inline]
    pub fn length_squared(self) -> f32 {
        self.x * self.x + self.y * self.y + self.z * self.z + self.w * self.w
    }

    #[inline]
    pub fn length(self) -> f32 {
        self.length_squared().sqrt()
    }

    #[inline]
    pub fn normalize(self) -> Self {
        let len = self.length();
        if len > 1e-6 {
            Self {
                x: self.x / len,
                y: self.y / len,
                z: self.z / len,
                w: self.w / len,
            }
        } else {
            Self::IDENTITY
        }
    }

}

impl std::ops::Mul for Quat {
    type Output = Self;

    #[inline]
    fn mul(self, rhs: Self) -> Self {
        Self {
            w: self.w * rhs.w - self.x * rhs.x - self.y * rhs.y - self.z * rhs.z,
            x: self.w * rhs.x + self.x * rhs.w + self.y * rhs.z - self.z * rhs.y,
            y: self.w * rhs.y - self.x * rhs.z + self.y * rhs.w + self.z * rhs.x,
            z: self.w * rhs.z + self.x * rhs.y - self.y * rhs.x + self.z * rhs.w,
        }
    }
}

impl Quat {

    /// Rotaciona um vetor 3D pelo quaternion: $v' = q \cdot v \cdot q^{-1}$.
    pub fn rotate_vec3(self, v: Vec3) -> Vec3 {
        // Fórmula otimizada: v' = v + 2 * cross(q.xyz, cross(q.xyz, v) + q.w * v)
        let qv = Vec3::new(self.x, self.y, self.z);
        let uv = Vec3::new(
            qv.y * v.z - qv.z * v.y,
            qv.z * v.x - qv.x * v.z,
            qv.x * v.y - qv.y * v.x,
        );
        let uuv = Vec3::new(
            qv.y * uv.z - qv.z * uv.y,
            qv.z * uv.x - qv.x * uv.z,
            qv.x * uv.y - qv.y * uv.x,
        );
        Vec3::new(
            v.x + ((uv.x * self.w) + uuv.x) * 2.0,
            v.y + ((uv.y * self.w) + uuv.y) * 2.0,
            v.z + ((uv.z * self.w) + uuv.z) * 2.0,
        )
    }

    /// Converte o quaternion para uma matriz de rotação 3x3 (row-major).
    pub fn to_rotation_matrix_3x3(self) -> [[f32; 3]; 3] {
        let q = self.normalize();
        let xx = q.x * q.x;
        let yy = q.y * q.y;
        let zz = q.z * q.z;
        let xy = q.x * q.y;
        let xz = q.x * q.z;
        let yz = q.y * q.z;
        let wx = q.w * q.x;
        let wy = q.w * q.y;
        let wz = q.w * q.z;

        [
            [1.0 - 2.0 * (yy + zz), 2.0 * (xy - wz), 2.0 * (xz + wy)],
            [2.0 * (xy + wz), 1.0 - 2.0 * (xx + zz), 2.0 * (yz - wx)],
            [2.0 * (xz - wy), 2.0 * (yz + wx), 1.0 - 2.0 * (xx + yy)],
        ]
    }

    /// Cria um quaternion a partir de um ângulo em torno do eixo Y (Yaw em radianos).
    pub fn from_axis_angle_y(angle_rad: f32) -> Self {
        let half = angle_rad * 0.5;
        Self {
            x: 0.0,
            y: half.sin(),
            z: 0.0,
            w: half.cos(),
        }
    }

    /// Interpolação esférica linear (Slerp) entre `self` e `other` pelo fator `t` em `[0.0, 1.0]`.
    pub fn slerp(self, mut other: Self, t: f32) -> Self {
        let mut dot = self.x * other.x + self.y * other.y + self.z * other.z + self.w * other.w;

        // Se dot < 0, inverter um dos quaternions para tomar o caminho mais curto
        if dot < 0.0 {
            other = Self {
                x: -other.x,
                y: -other.y,
                z: -other.z,
                w: -other.w,
            };
            dot = -dot;
        }

        if dot > 0.9995 {
            // Quaternions quase idênticos -> interpolação linear para evitar divisão por zero
            Self {
                x: self.x + t * (other.x - self.x),
                y: self.y + t * (other.y - self.y),
                z: self.z + t * (other.z - self.z),
                w: self.w + t * (other.w - self.w),
            }
            .normalize()
        } else {
            let theta_0 = dot.clamp(-1.0, 1.0).acos();
            let theta = theta_0 * t;
            let sin_theta = theta.sin();
            let sin_theta_0 = theta_0.sin();

            let s0 = (theta_0 - theta).sin() / sin_theta_0;
            let s1 = sin_theta / sin_theta_0;

            Self {
                x: s0 * self.x + s1 * other.x,
                y: s0 * self.y + s1 * other.y,
                z: s0 * self.z + s1 * other.z,
                w: s0 * self.w + s1 * other.w,
            }
            .normalize()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_vec3_spherical_conversion() {
        let forward = Vec3::FORWARD; // (0, 0, -1)
        let (az, el) = forward.to_spherical_degrees();
        assert!((az - 0.0).abs() < 1e-3);
        assert!((el - 0.0).abs() < 1e-3);

        let right = Vec3::RIGHT; // (1, 0, 0)
        let (az_r, el_r) = right.to_spherical_degrees();
        assert!((az_r - 90.0).abs() < 1e-3);
        assert!((el_r - 0.0).abs() < 1e-3);

        let up = Vec3::UP; // (0, 1, 0)
        let (_az_u, el_u) = up.to_spherical_degrees();
        assert!((el_u - 90.0).abs() < 1e-3);

        let from_sp = Vec3::from_spherical_degrees(90.0, 0.0, 1.0);
        assert!((from_sp.x - 1.0).abs() < 1e-3);
        assert!((from_sp.y - 0.0).abs() < 1e-3);
        assert!(from_sp.z.abs() < 1e-3);
    }

    #[test]
    fn test_quaternion_rotation_and_inverse() {
        // Rotação de 90° em torno de Y (olhar para a direita)
        let q = Quat::from_axis_angle_y(std::f32::consts::FRAC_PI_2);
        let forward = Vec3::FORWARD; // (0, 0, -1)

        // Girar para a direita: vetor frente (0, 0, -1) rotaciona para (-1, 0, 0)
        let rotated = q.rotate_vec3(forward);
        assert!((rotated.x - (-1.0)).abs() < 1e-4);
        assert!(rotated.y.abs() < 1e-4);
        assert!(rotated.z.abs() < 1e-4);

        // A inversa (conjugate) desfaz a rotação
        let unrotated = q.conjugate().rotate_vec3(rotated);
        assert!(unrotated.x.abs() < 1e-4);
        assert!(unrotated.y.abs() < 1e-4);
        assert!((unrotated.z - (-1.0)).abs() < 1e-4);
    }

    #[test]
    fn test_quaternion_slerp() {
        let q0 = Quat::IDENTITY;
        let q1 = Quat::from_axis_angle_y(std::f32::consts::FRAC_PI_2); // 90°

        let mid = q0.slerp(q1, 0.5); // 45°
        let rotated = mid.rotate_vec3(Vec3::FORWARD);
        // Em 45° à esquerda: x = -sin(45°) = -0.7071, z = -cos(45°) = -0.7071
        assert!((rotated.x - (-std::f32::consts::FRAC_1_SQRT_2)).abs() < 1e-3);
        assert!((rotated.z - (-std::f32::consts::FRAC_1_SQRT_2)).abs() < 1e-3);
    }
}
