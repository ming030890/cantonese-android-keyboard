plugins {
    id("com.android.asset-pack")
}

// Shard 1 of the Qwen ASR model + tokenizer files. The full model is split
// into two fast-follow asset packs (a, b) so each stays under Play's 512 MiB
// per-pack limit. The native loader transparently reads sharded safetensors
// (model-XXXXX-of-YYYYY.safetensors) via MultiSafetensors::open.
assetPack {
    packName.set("model_pack_a")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}
