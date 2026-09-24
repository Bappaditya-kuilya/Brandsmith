package com.brandsmith.api.budget;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BudgetGuard {

    private final String mainModel;
    private final double mainInputPerMtok;
    private final double mainOutputPerMtok;
    private final double smallInputPerMtok;
    private final double smallOutputPerMtok;

    public BudgetGuard(@Value("${brandsmith.llm.main-model}") String mainModel,
                       @Value("${brandsmith.llm.pricing.main-input-per-mtok:3.0}") double mainInputPerMtok,
                       @Value("${brandsmith.llm.pricing.main-output-per-mtok:15.0}") double mainOutputPerMtok,
                       @Value("${brandsmith.llm.pricing.small-input-per-mtok:1.0}") double smallInputPerMtok,
                       @Value("${brandsmith.llm.pricing.small-output-per-mtok:5.0}") double smallOutputPerMtok) {
        this.mainModel = mainModel;
        this.mainInputPerMtok = mainInputPerMtok;
        this.mainOutputPerMtok = mainOutputPerMtok;
        this.smallInputPerMtok = smallInputPerMtok;
        this.smallOutputPerMtok = smallOutputPerMtok;
    }

    public double cost(String model, int tokensIn, int tokensOut) {
        boolean main = model != null && model.equals(mainModel);
        double inPrice = main ? mainInputPerMtok : smallInputPerMtok;
        double outPrice = main ? mainOutputPerMtok : smallOutputPerMtok;
        return tokensIn / 1_000_000.0 * inPrice + tokensOut / 1_000_000.0 * outPrice;
    }

    public void ensureWithinCap(double spentUsd, double capUsd) {
        if (spentUsd >= capUsd) {
            throw new BudgetExceededException(
                    "Session budget cap of $" + String.format("%.2f", capUsd) + " USD reached. Start a new session to continue.");
        }
    }
}
