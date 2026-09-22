# ICARUS Premium pricing

US base prices:
- Monthly: $10.00
- Every 3 months: $17.42
- Annual: $69.69

Google Play product IDs:
- `icarus_pro_monthly`
- `icarus_pro_quarterly`
- `icarus_pro_annual`

The listed Play price is the customer-facing base price. Google Play service/billing fees are deducted from developer proceeds rather than added to the configured price. Applicable tax can change the buyer's checkout total by jurisdiction.

Developer-granted Premium is separate from Play purchases. The server recognizes only accounts listed in the server-controlled `ICARUS_PREMIUM_GRANT_EMAILS` environment variable. Normal clients cannot write this entitlement.

Before production monetization, server-side verification of Play purchase tokens, expiry, grace/hold, cancellation and replay protection remains a release gate.
