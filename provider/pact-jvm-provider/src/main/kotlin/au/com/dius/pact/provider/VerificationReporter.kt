package au.com.dius.pact.provider

import arrow.core.Either
import au.com.dius.pact.core.model.BrokerUrlSource
import au.com.dius.pact.core.model.Interaction
import au.com.dius.pact.core.model.Pact
import au.com.dius.pact.core.pactbroker.PactBrokerClient
import au.com.dius.pact.core.pactbroker.TestResult
import mu.KLogging

/**
 * Interface to the reporter that published the verification results
 */
interface VerificationReporter {
  /**
   * Publish the results to the pact broker. If the tag is given, then the provider will be tagged with that first.
   */
  fun reportResults(
    pact: Pact<out Interaction>,
    result: TestResult,
    version: String,
    client: PactBrokerClient? = null,
    tag: String? = null
  )

  /**
   * This must return true unless the pact.verifier.publishResults property has the value of "true"
   */
  fun publishingResultsDisabled(): Boolean
}

/**
 * Default implementation of a verification reporter
 */
object DefaultVerificationReporter : VerificationReporter, KLogging() {

  override fun reportResults(
    pact: Pact<out Interaction>,
    result: TestResult,
    version: String,
    client: PactBrokerClient?,
    tag: String?
  ) {
    when (val source = pact.source) {
      is BrokerUrlSource -> {
        val brokerClient = client ?: PactBrokerClient(source.pactBrokerUrl, source.options)
        publishResult(brokerClient, source, result, version, pact, tag)
      }
      else -> logger.info { "Skipping publishing verification results for source $source" }
    }
  }

  private fun <I : Interaction> publishResult(
    brokerClient: PactBrokerClient,
    source: BrokerUrlSource,
    result: TestResult,
    version: String,
    pact: Pact<out I>,
    tag: String?
  ) {
    if (!tag.isNullOrEmpty()) {
      brokerClient.publishProviderTag(source.attributes, pact.provider.name, tag, version)
    }
    val publishResult = brokerClient.publishVerificationResults(source.attributes, result, version)
    if (publishResult is Either.Left) {
      logger.error { "Failed to publish verification results - ${publishResult.a}" }
    } else {
      logger.info { "Published verification result of '$result' for consumer '${pact.consumer}'" }
    }
  }

  override fun publishingResultsDisabled(): Boolean {
    var property = System.getProperty(ProviderVerifier.PACT_VERIFIER_PUBLISH_RESULTS)
    if (property.isNullOrEmpty()) {
      property = System.getenv(ProviderVerifier.PACT_VERIFIER_PUBLISH_RESULTS)
    }
    return property?.toLowerCase() != "true"
  }
}
