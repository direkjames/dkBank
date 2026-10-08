package dev.direk.dkbank.bank;

import dev.direk.dkbank.api.BankResult;
import dev.direk.dkbank.storage.StoreTypes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTypesTest {

    @Test
    void everyStoreFailureHasAnApiFailure() {
        for (StoreTypes.Failure f : StoreTypes.Failure.values()) {
            BankResult.Failure mapped = ApiTypes.failure(f);
            assertTrue(mapped != null, f.name());
        }
        assertEquals(BankResult.Failure.INSUFFICIENT_FUNDS, ApiTypes.failure(StoreTypes.Failure.INSUFFICIENT_FUNDS));
        assertEquals(BankResult.Failure.INVALID_AMOUNT, ApiTypes.failure(StoreTypes.Failure.BELOW_MINIMUM));
    }

    @Test
    void failedResultsKeepTheBalance() {
        BankResult r = ApiTypes.result(new StoreTypes.Result(StoreTypes.Failure.INSUFFICIENT_FUNDS,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("12.50"), new BigDecimal("12.50")));
        assertEquals(new BigDecimal("12.50"), r.balance());
        assertEquals(false, r.success());
    }
}
