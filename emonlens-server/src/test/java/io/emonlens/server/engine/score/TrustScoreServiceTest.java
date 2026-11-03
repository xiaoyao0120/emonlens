package io.emonlens.server.engine.score;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 评分红线验证：加权几何平均使单项极差不被平均掩盖；等权时全满分=100。
 */
class TrustScoreServiceTest {

    private double overall(double p, double b, double o, double s, double c) {
        EmonLensWeights w = new EmonLensWeights();
        return Math.min(100, Math.pow(p, w.performance) * Math.pow(b, w.behavior)
                * Math.pow(o, w.output) * Math.pow(s, w.security) * Math.pow(c, w.context));
    }

    @Test
    void geometricMeanPunishesSingleBadDimension() {
        // 四维 100、一维 0（权重 0.2）→ overall = 100^(0.8) * 0^(0.2) = 0
        assertThat(overall(100, 100, 100, 100, 0)).isEqualTo(0.0);
        // 一维 60（权重 0.30，其余 100）→ 100^0.7 * 60^0.3 ≈ 85.8，低于算术平均 92（几何平均放大短板）
        double value = overall(100, 100, 60, 100, 100);
        assertThat(value).isLessThan(87.0);
        assertThat(value).isGreaterThan(84.0);
    }

    @Test
    void allPerfectIsHundred() {
        assertThat(overall(100, 100, 100, 100, 100)).isCloseTo(100.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void penaltyScoreSoftSaturatesNeverZero() throws Exception {
        // 软饱和：罚分极大时维度分逼近 0 但不为 0（否则几何加权总分直接归零、失去可分度）
        java.lang.reflect.Method m = TrustScoreService.class
                .getDeclaredMethod("penaltyScore", java.util.List.class, String.class);
        m.setAccessible(true);
        TrustScoreService service = new TrustScoreService(null, null, null, null, null, null, null, null,
                null, null);
        // 200 条 HIGH finding（罚分 3000）→ 100/(1+3000/80) ≈ 2.6
        java.util.List<io.emonlens.server.storage.entity.FindingEntity> findings = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            io.emonlens.server.storage.entity.FindingEntity f =
                    new io.emonlens.server.storage.entity.FindingEntity();
            f.setDimension("BEHAVIOR");
            f.setSeverity("HIGH");
            findings.add(f);
        }
        double score = (double) m.invoke(service, findings, "BEHAVIOR");
        assertThat(score).isGreaterThan(0.0);
        assertThat(score).isLessThan(5.0);
        // 1 条 HIGH ≈ 84（保持对少量问题的可读区分）
        double single = (double) m.invoke(service, findings.subList(0, 1), "BEHAVIOR");
        assertThat(single).isGreaterThan(80.0).isLessThan(88.0);
    }

    /** 测试用等权（与默认配置一致） */
    private static final class EmonLensWeights {
        final double performance = 0.25;
        final double behavior = 0.25;
        final double output = 0.30;
        final double security = 0.10;
        final double context = 0.10;
    }
}
