package dev.direk.dkbank.bank;

import dev.direk.dkbank.api.BankResult;
import dev.direk.dkbank.api.BankTier;
import dev.direk.dkbank.storage.StoreTypes;
import dev.direk.dkbank.tier.Tier;

/** Turns dkBank's own types into the Developer API's. */
public final class ApiTypes {

    private ApiTypes() {
    }

    public static BankTier tier(Tier tier) {
        return new BankTier(tier.id(), tier.rank(), tier.displayName(), tier.cost(), tier.buyable(), tier.maxBalance(),
                tier.plan().onlineRate(), tier.plan().offlineRate(), tier.plan().cap());
    }

    public static BankResult result(StoreTypes.Result r) {
        if (r.ok()) return new BankResult(null, r.amount(), r.balance());
        return new BankResult(failure(r.failure()), r.amount(), r.available());
    }

    static BankResult.Failure failure(StoreTypes.@org.jspecify.annotations.Nullable Failure failure) {
        if (failure == null) return BankResult.Failure.ERROR;
        return switch (failure) {
            case NO_ACCOUNT -> BankResult.Failure.NO_ACCOUNT;
            case INSUFFICIENT_FUNDS -> BankResult.Failure.INSUFFICIENT_FUNDS;
            case BALANCE_LIMIT -> BankResult.Failure.BALANCE_LIMIT;
            case NOTHING_TO_MOVE, BELOW_MINIMUM -> BankResult.Failure.INVALID_AMOUNT;
            case TIER_CHANGED -> BankResult.Failure.ERROR;
        };
    }
}
