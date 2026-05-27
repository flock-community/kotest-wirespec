package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
public val ScenarioBuilder.wirespec: WirespecCatalog
    get() = WirespecCatalog(this)
@WirespecScenarioDsl
public class WirespecCatalog internal constructor(private val scenario: ScenarioBuilder) {
    public val petCreate: PetCreateCall
        get() = PetCreateCall(scenario)
    public val petGet: PetGetCall
        get() = PetGetCall(scenario)
    public val petCreatedChannel: PetCreatedChannelCall
        get() = PetCreatedChannelCall(scenario)
}
