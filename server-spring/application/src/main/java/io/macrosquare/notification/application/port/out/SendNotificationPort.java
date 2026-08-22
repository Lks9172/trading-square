package io.macrosquare.notification.application.port.out;

import io.macrosquare.notification.application.model.NotificationDeliveryReceipt;

public interface SendNotificationPort {
    NotificationDeliveryReceipt send(String idempotencyKey, String text);
}
