package io.kotest.extensions.wirespec.dsl

import community.flock.wirespec.kotlin.Wirespec
import kotlin.reflect.KClass

/**
 * Build an [EndpointCallBuilder] for an endpoint. Generated `*Call` wrappers
 * call this instead of the removed `ScenarioBuilder.endpoint(...)`.
 */
fun <BodyT : Any, Req : Wirespec.Request<BodyT>, Resp : Wirespec.Response<*>> endpointCall(
    client: Wirespec.Client<Req, Resp>,
    endpointObject: Wirespec.Endpoint,
): EndpointCallBuilder<BodyT, Req, Resp> = EndpointCallBuilder(client, endpointObject)

/** Build a [ChannelCallBuilder] for a channel. */
fun <MessageT : Any> channelCall(
    channelClass: KClass<out Wirespec.Channel>,
): ChannelCallBuilder<MessageT> = ChannelCallBuilder(channelClass)
