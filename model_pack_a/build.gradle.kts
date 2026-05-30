plugins {
    // Applied without a version: the asset-pack plugin is already on the
    // build classpath via the Android Gradle Plugin.
    id("com.android.asset-pack")
}

// Play Asset Delivery pack that carries the Qwen ASR model files.
// "fast-follow" means the pack downloads automatically right after the app is
// installed from Play, without blocking the install itself. The app accesses
// the extracted files on disk via AssetPackManager.getPackLocation(...).
assetPack {
    packName.set("model_pack")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}
