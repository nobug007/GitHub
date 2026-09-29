package com.sf.streamingesp

/**
 * 전신 리그 정의와, YOLOv8-pose 의 COCO 17점을 요청된 20점 리그로 바꾸는 변환.
 *
 * YOLOv8-pose 가 주는 것은 COCO 17점 — 코, 눈/귀, 어깨, 팔꿈치, 손목, 골반(엉덩이), 무릎,
 * 발목뿐이다. 머리·목·허리·골반중심·손·발끝은 원래 없는 점이라 아래에서 만들어낸다.
 * 손과 발끝은 팔뚝/정강이 방향으로 연장한 추정치다. 실제 손가락·발가락 랜드마크가 필요하면
 * BlazePose(33점) 계열로 바꿔야 하고, 대신 속도를 내주게 된다.
 */
object Rig {

    /** 화면에 그려질 관절. 인덱스가 곧 [Pose.points] 의 인덱스다. */
    const val HEAD = 0
    const val NECK = 1
    const val WAIST = 2
    const val PELVIS = 3
    const val SHOULDER_L = 4
    const val SHOULDER_R = 5
    const val ELBOW_L = 6
    const val ELBOW_R = 7
    const val WRIST_L = 8
    const val WRIST_R = 9
    const val HAND_L = 10
    const val HAND_R = 11
    const val HIP_L = 12
    const val HIP_R = 13
    const val KNEE_L = 14
    const val KNEE_R = 15
    const val ANKLE_L = 16
    const val ANKLE_R = 17
    const val TOE_L = 18
    const val TOE_R = 19

    const val JOINT_COUNT = 20

    /** 노란 선으로 이어질 뼈대. */
    val BONES = arrayOf(
        intArrayOf(HEAD, NECK),
        intArrayOf(NECK, WAIST),
        intArrayOf(WAIST, PELVIS),
        intArrayOf(NECK, SHOULDER_L),
        intArrayOf(NECK, SHOULDER_R),
        intArrayOf(SHOULDER_L, ELBOW_L),
        intArrayOf(ELBOW_L, WRIST_L),
        intArrayOf(WRIST_L, HAND_L),
        intArrayOf(SHOULDER_R, ELBOW_R),
        intArrayOf(ELBOW_R, WRIST_R),
        intArrayOf(WRIST_R, HAND_R),
        intArrayOf(PELVIS, HIP_L),
        intArrayOf(PELVIS, HIP_R),
        intArrayOf(HIP_L, KNEE_L),
        intArrayOf(KNEE_L, ANKLE_L),
        intArrayOf(ANKLE_L, TOE_L),
        intArrayOf(HIP_R, KNEE_R),
        intArrayOf(KNEE_R, ANKLE_R),
        intArrayOf(ANKLE_R, TOE_R)
    )

    // COCO 17 인덱스
    private const val C_NOSE = 0
    private const val C_SHOULDER_L = 5
    private const val C_SHOULDER_R = 6
    private const val C_ELBOW_L = 7
    private const val C_ELBOW_R = 8
    private const val C_WRIST_L = 9
    private const val C_WRIST_R = 10
    private const val C_HIP_L = 11
    private const val C_HIP_R = 12
    private const val C_KNEE_L = 13
    private const val C_KNEE_R = 14
    private const val C_ANKLE_L = 15
    private const val C_ANKLE_R = 16

    /**
     * 한 사람의 리그. [valid] 가 false 인 관절은 그리지 않는다 — 가려졌거나 화면 밖이라
     * 신뢰도가 낮은 점을 억지로 찍으면 스켈레톤이 엉뚱한 데로 뻗는다.
     */
    class Pose {
        val x = FloatArray(JOINT_COUNT)
        val y = FloatArray(JOINT_COUNT)
        val valid = BooleanArray(JOINT_COUNT)

        val visibleCount: Int get() = valid.count { it }
    }

    /**
     * COCO 17점(원본 프레임 좌표계) → 20점 리그.
     *
     * @param kx COCO 키포인트 x, 길이 17
     * @param ky COCO 키포인트 y, 길이 17
     * @param ks COCO 키포인트 신뢰도, 길이 17
     * @param minScore 이 값 미만이면 없는 점으로 취급
     */
    fun build(kx: FloatArray, ky: FloatArray, ks: FloatArray, minScore: Float, out: Pose) {
        java.util.Arrays.fill(out.valid, false)

        fun copy(dst: Int, srcIdx: Int) {
            if (ks[srcIdx] >= minScore) {
                out.x[dst] = kx[srcIdx]
                out.y[dst] = ky[srcIdx]
                out.valid[dst] = true
            }
        }

        copy(SHOULDER_L, C_SHOULDER_L)
        copy(SHOULDER_R, C_SHOULDER_R)
        copy(ELBOW_L, C_ELBOW_L)
        copy(ELBOW_R, C_ELBOW_R)
        copy(WRIST_L, C_WRIST_L)
        copy(WRIST_R, C_WRIST_R)
        copy(HIP_L, C_HIP_L)
        copy(HIP_R, C_HIP_R)
        copy(KNEE_L, C_KNEE_L)
        copy(KNEE_R, C_KNEE_R)
        copy(ANKLE_L, C_ANKLE_L)
        copy(ANKLE_R, C_ANKLE_R)

        mid(out, NECK, SHOULDER_L, SHOULDER_R)
        mid(out, PELVIS, HIP_L, HIP_R)
        // 허리는 목과 골반 사이의 아래쪽. 0.5 로 두면 명치에 가까워 몸통이 접힌 것처럼 보인다.
        lerp(out, WAIST, NECK, PELVIS, 0.55f)

        // 머리는 코를 목의 반대 방향으로 조금 밀어 정수리 쪽에 둔다. 코 위치에 그대로 찍으면
        // 목-머리 선이 얼굴 한가운데서 끝나 리그처럼 보이지 않는다.
        if (ks[C_NOSE] >= minScore) {
            if (out.valid[NECK]) {
                out.x[HEAD] = kx[C_NOSE] + (kx[C_NOSE] - out.x[NECK]) * 0.35f
                out.y[HEAD] = ky[C_NOSE] + (ky[C_NOSE] - out.y[NECK]) * 0.35f
            } else {
                out.x[HEAD] = kx[C_NOSE]
                out.y[HEAD] = ky[C_NOSE]
            }
            out.valid[HEAD] = true
        }

        extend(out, HAND_L, ELBOW_L, WRIST_L, 0.30f)
        extend(out, HAND_R, ELBOW_R, WRIST_R, 0.30f)
        extend(out, TOE_L, KNEE_L, ANKLE_L, 0.35f)
        extend(out, TOE_R, KNEE_R, ANKLE_R, 0.35f)
    }

    private fun mid(p: Pose, dst: Int, a: Int, b: Int) {
        if (!p.valid[a] || !p.valid[b]) return
        p.x[dst] = (p.x[a] + p.x[b]) * 0.5f
        p.y[dst] = (p.y[a] + p.y[b]) * 0.5f
        p.valid[dst] = true
    }

    private fun lerp(p: Pose, dst: Int, a: Int, b: Int, t: Float) {
        if (!p.valid[a] || !p.valid[b]) return
        p.x[dst] = p.x[a] + (p.x[b] - p.x[a]) * t
        p.y[dst] = p.y[a] + (p.y[b] - p.y[a]) * t
        p.valid[dst] = true
    }

    /** from → to 방향으로 그 길이의 [t] 배만큼 더 나아간 점. 손끝/발끝 추정용. */
    private fun extend(p: Pose, dst: Int, from: Int, to: Int, t: Float) {
        if (!p.valid[from] || !p.valid[to]) return
        p.x[dst] = p.x[to] + (p.x[to] - p.x[from]) * t
        p.y[dst] = p.y[to] + (p.y[to] - p.y[from]) * t
        p.valid[dst] = true
    }
}
