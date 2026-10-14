package com.tiam.pack.domain;

import com.tiam.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "pack_orders")
@Getter
@Setter
public class PackOrder extends BaseEntity {

    @Column(name = "access_token", nullable = false, unique = true, length = 64)
    private String accessToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PackOrderStatus status = PackOrderStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PackEdition edition;

    @Column(name = "with_games", nullable = false)
    private boolean withGames;

    @Column(name = "with_extra", nullable = false)
    private boolean withExtra;

    @Column(nullable = false, length = 3)
    private String currency = "ARS";

    @Column(name = "pack_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal packAmount;

    @Column(name = "games_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal gamesAmount = BigDecimal.ZERO;

    @Column(name = "extra_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal extraAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "buyer_name", nullable = false)
    private String buyerName;

    @Column(nullable = false)
    private String email;

    @Column(length = 50)
    private String phone;

    /** Storage key version this order downloads from; new catalog versions never touch old orders. */
    @Column(name = "catalog_version", nullable = false, length = 20)
    private String catalogVersion = "v1";

    @Column(name = "mp_preference_id")
    private String mpPreferenceId;

    @Column(name = "init_point", columnDefinition = "TEXT")
    private String initPoint;

    @Column(name = "mp_payment_id")
    private String mpPaymentId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "access_expires_at")
    private Instant accessExpiresAt;

    /**
     * A second credential issued when the buyer asks for their link again ("I lost it"). It lives next to the
     * working {@link #accessToken} instead of replacing it, so a stranger who knows the email cannot break the
     * buyer's link by asking: the first time the NEW token is used it becomes the access token and the old one
     * stops working. Null when none is pending.
     */
    @Column(name = "next_access_token", length = 64)
    private String nextAccessToken;

    @Column(name = "next_access_token_issued_at")
    private Instant nextAccessTokenIssuedAt;

    /** When the access email was accepted by the provider; null = not delivered yet. */
    @Column(name = "email_sent_at")
    private Instant emailSentAt;

    /** The Desafío month bundled with the games add-on (a PAID challenge purchase), when bought. */
    @Column(name = "challenge_purchase_id")
    private Long challengePurchaseId;

    @Column(name = "utm_source", length = 100)
    private String utmSource;

    @Column(name = "utm_medium", length = 100)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 100)
    private String utmCampaign;

    @Column(name = "utm_content", length = 100)
    private String utmContent;

    /** The external_reference Mercado Pago carries for this order, e.g. {@code pack:42}. */
    public String externalReference() {
        return PackExternalReference.of(getId());
    }

    /** True while the buyer may download: paid, active and inside the access window. */
    public boolean isDownloadable(Instant now) {
        return status == PackOrderStatus.PAID
                && isActivo()
                && accessExpiresAt != null
                && now.isBefore(accessExpiresAt);
    }
}
