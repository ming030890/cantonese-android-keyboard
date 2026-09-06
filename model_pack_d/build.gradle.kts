plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("model_pack_d")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}
