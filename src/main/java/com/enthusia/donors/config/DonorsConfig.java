package com.enthusia.donors.config;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Set;

public record DonorsConfig(
        String tebexApiKey,
        int refreshIntervalMinutes,
        int timeoutSeconds,
        int maxRetries,
        int maxPages,
        String countingSource,
        boolean countZeroDollarPayments,
        boolean countManualPayments,
        boolean countRefundedPayments,
        boolean countChargebackPayments,
        Set<Integer> includedPackageIds,
        Set<Integer> excludedPackageIds,
        Set<String> excludedTransactionIds,
        Set<String> excludedPaymentIdHashes,
        ZoneId timezone,
        String currencySymbol,
        boolean showCents,
        int topSize,
        String emptyName,
        String emptyAmount,
        String emptyRank,
        boolean useSqlite,
        boolean saveJsonExport,
        Path jsonExportPath,
        boolean r2UploadEnabled,
        String r2AccountId,
        String r2Endpoint,
        String r2AccessKeyId,
        String r2SecretAccessKey,
        String r2Bucket,
        String r2ObjectPath,
        String r2IndexObjectPath,
        boolean exposeRawPaymentData,
        boolean hashPaymentIds,
        boolean fakeDataEnabled,
        int fakePlayerCount
) {
    public DonorsConfig withSharedDisplay(ZoneId zone,String symbol,boolean cents,int size,String emptyName,String emptyAmount,String emptyRank) {
        return new DonorsConfig(tebexApiKey,refreshIntervalMinutes,timeoutSeconds,maxRetries,maxPages,countingSource,
                countZeroDollarPayments,countManualPayments,countRefundedPayments,countChargebackPayments,
                includedPackageIds,excludedPackageIds,excludedTransactionIds,excludedPaymentIdHashes,
                zone,symbol,cents,size,emptyName,emptyAmount,emptyRank,useSqlite,saveJsonExport,jsonExportPath,
                r2UploadEnabled,r2AccountId,r2Endpoint,r2AccessKeyId,r2SecretAccessKey,r2Bucket,r2ObjectPath,r2IndexObjectPath,
                exposeRawPaymentData,hashPaymentIds,fakeDataEnabled,fakePlayerCount);
    }
}
