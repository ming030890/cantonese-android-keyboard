plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("model_pack_c")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}
