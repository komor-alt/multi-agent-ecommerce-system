package com.ecommerce.aftersales.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OperatorContext 校验测试：只接受可信 Gateway 值（非空 + ^[A-Za-z0-9._-]{2,64}$），
 * 缺失抛 OPERATOR_IDENTITY_REQUIRED，格式/长度非法抛 OPERATOR_IDENTITY_INVALID。
 */
class OperatorContextTest {

    @Test
    void validHeaderValueBuildsContextWithTrimmedId() {
        OperatorContext context = OperatorContext.fromTrustedGatewayHeader("  operator-vn-01  ");

        assertThat(context.id()).isEqualTo("operator-vn-01");
    }

    @Test
    void validFormatsAreAccepted() {
        assertThat(OperatorContext.fromTrustedGatewayHeader("op").id()).isEqualTo("op");
        assertThat(OperatorContext.fromTrustedGatewayHeader("operator.vn-01_ops").id())
                .isEqualTo("operator.vn-01_ops");
        assertThat(OperatorContext.fromTrustedGatewayHeader("a".repeat(64)).id()).hasSize(64);
    }

    @Test
    void missingHeaderValueThrowsIdentityRequired() {
        assertIdentityRequired(() -> OperatorContext.fromTrustedGatewayHeader(null));
    }

    @Test
    void blankHeaderValueThrowsIdentityRequired() {
        assertIdentityRequired(() -> OperatorContext.fromTrustedGatewayHeader("   "));
    }

    @Test
    void tooShortValueThrowsIdentityInvalid() {
        assertIdentityInvalid(() -> OperatorContext.fromTrustedGatewayHeader("a"));
    }

    @Test
    void tooLongValueThrowsIdentityInvalid() {
        assertIdentityInvalid(() -> OperatorContext.fromTrustedGatewayHeader("a".repeat(65)));
    }

    @Test
    void valueWithIllegalCharactersThrowsIdentityInvalid() {
        assertIdentityInvalid(() -> OperatorContext.fromTrustedGatewayHeader("operator vn"));
        assertIdentityInvalid(() -> OperatorContext.fromTrustedGatewayHeader("operator/vn"));
        assertIdentityInvalid(() -> OperatorContext.fromTrustedGatewayHeader("operator+v"));
        assertIdentityInvalid(() -> OperatorContext.fromTrustedGatewayHeader("运营专员"));
    }

    private static void assertIdentityRequired(OperatorContextFactory factory) {
        assertThatThrownBy(factory::create)
                .isInstanceOfSatisfying(OperatorIdentityViolationException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo(OperatorIdentityViolationException.OPERATOR_IDENTITY_REQUIRED));
    }

    private static void assertIdentityInvalid(OperatorContextFactory factory) {
        assertThatThrownBy(factory::create)
                .isInstanceOfSatisfying(OperatorIdentityViolationException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo(OperatorIdentityViolationException.OPERATOR_IDENTITY_INVALID));
    }

    @FunctionalInterface
    private interface OperatorContextFactory {
        OperatorContext create();
    }
}
