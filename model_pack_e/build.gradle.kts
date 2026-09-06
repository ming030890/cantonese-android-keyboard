plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("model_pack_e")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}
