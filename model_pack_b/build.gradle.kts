plugins {
    id("com.android.asset-pack")
}

// Shard 2 of the Qwen ASR model. See model_pack_a/build.gradle.kts for
// details on the two-pack split.
assetPack {
    packName.set("model_pack_b")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}
