package io.macrosquare.notification.application.model;

/** Transport-neutral delivery outcome. Provider identifiers are diagnostic only. */
public record NotificationDeliveryReceipt(
        boolean delivered,
        String providerMessageId,
        String failureCode
) {
    public NotificationDeliveryReceipt {
        providerMessageId = providerMessageId == null ? "" : providerMessageId;
        failureCode = failureCode == null ? "" : failureCode;
        if (delivered && !failureCode.isBlank()) {
            throw new IllegalArgumentException("a delivered receipt cannot contain a failure code");
        }
    }

    public static NotificationDeliveryReceipt delivered(String providerMessageId) {
        return new NotificationDeliveryReceipt(true, providerMessageId, "");
    }

    public static NotificationDeliveryReceipt failed(String failureCode) {
        return new NotificationDeliveryReceipt(false, "", failureCode);
    }
}
