package au.com.dius.pact.provider.junit.loader

import arrow.core.Either
import au.com.dius.pact.core.model.BrokerUrlSource
import au.com.dius.pact.core.model.Pact
import au.com.dius.pact.core.model.PactBrokerSource
import au.com.dius.pact.core.pactbroker.ConsumerVersionSelector
import au.com.dius.pact.core.pactbroker.IHalClient
import au.com.dius.pact.core.pactbroker.InvalidHalResponse
import au.com.dius.pact.core.pactbroker.PactBrokerClient
import au.com.dius.pact.core.pactbroker.PactResult
import au.com.dius.pact.core.support.expressions.SystemPropertyResolver
import au.com.dius.pact.core.support.expressions.ValueResolver
import au.com.dius.pact.provider.ConsumerInfo
import spock.lang.Specification
import spock.util.environment.RestoreSystemProperties

import static au.com.dius.pact.core.support.expressions.ExpressionParser.VALUES_SEPARATOR

@SuppressWarnings('LineLength')
class PactBrokerLoaderSpec extends Specification {

  private Closure<PactBrokerLoader> pactBrokerLoader
  private String host
  private String port
  private String protocol
  private List tags
  private List consumers
  private PactBrokerClient brokerClient
  private Pact mockPact

  void setup() {
    host = 'pactbroker'
    port = '1234'
    protocol = 'http'
    tags = ['latest']
    consumers = []
    brokerClient = Mock(PactBrokerClient) {
      newHalClient() >> Stub(IHalClient)
    }
    mockPact = Mock(Pact)

    pactBrokerLoader = { boolean failIfNoPactsFound = true ->
      PactBrokerClient client = brokerClient
      new PactBrokerLoader(host, port, protocol, tags, consumers, failIfNoPactsFound, null, null, null) {
        @Override
        PactBrokerClient newPactBrokerClient(URI url, ValueResolver resolver) {
          client
        }

        @Override
        Pact loadPact(ConsumerInfo consumer, Map options) {
          mockPact
        }
      }
    }
  }

  def 'Returns an empty list if the pact broker client returns an empty list'() {
    when:
    def list = pactBrokerLoader().load('test')

    then:
    1 * brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Right([])
    notThrown(NoPactsFoundException)
    list.empty
  }

  def 'Returns Empty List if flagged to do so and the pact broker client returns an empty list'() {
    when:
    def result = pactBrokerLoader(false).load('test')

    then:
    1 * brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Right([])
    result == []
  }

  def 'Throws any Exception On Execution Exception'() {
    given:
    brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Left(new InvalidHalResponse('message'))

    when:
    pactBrokerLoader().load('test')

    then:
    thrown(InvalidHalResponse)
  }

  def 'Throws an Exception if the broker URL is invalid'() {
    given:
    host = '!@#%$^%$^^'

    when:
    pactBrokerLoader().load('test')

    then:
    thrown(IOException)
  }

  void 'Loads Pacts Configured From A Pact Broker Annotation'() {
    given:
    pactBrokerLoader = {
      new PactBrokerLoader(FullPactBrokerAnnotation.getAnnotation(PactBroker)) {
        @Override
        PactBrokerClient newPactBrokerClient(URI url, ValueResolver resolver) {
          assert url.host == 'pactbroker.host'
          assert url.port == 1000
          brokerClient
        }
      }
    }

    when:
    def result = pactBrokerLoader().load('test')

    then:
    result == []
    1 * brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Right([])
  }

  @RestoreSystemProperties
  void 'Uses fallback PactBroker System Properties'() {
    given:
    System.setProperty('pactbroker.host', 'my.pactbroker.host')
    System.setProperty('pactbroker.port', '4711')
    pactBrokerLoader = {
      new PactBrokerLoader(MinimalPactBrokerAnnotation.getAnnotation(PactBroker)) {
        @Override
        PactBrokerClient newPactBrokerClient(URI url, ValueResolver resolver) {
          assert url.host == 'my.pactbroker.host'
          assert url.port == 4711
          brokerClient
        }
      }
    }

    when:
    def result = pactBrokerLoader().load('test')

    then:
    result == []
    1 * brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Right([])
  }

  @RestoreSystemProperties
  void 'Uses fallback PactBroker System Properties for PactSource'() {
    given:
    host = 'my.pactbroker.host'
    port = '4711'
    System.setProperty('pactbroker.host', host)
    System.setProperty('pactbroker.port', port)

    when:
    def pactSource = new PactBrokerLoader(MinimalPactBrokerAnnotation.getAnnotation(PactBroker)).pactSource

    then:
    assert pactSource instanceof PactBrokerSource

    def pactBrokerSource = (PactBrokerSource) pactSource
    assert pactBrokerSource.scheme == 'http'
    assert pactBrokerSource.host == host
    assert pactBrokerSource.port == port
  }

  @RestoreSystemProperties
  void 'Fails when no fallback system properties are set'() {
    given:
    System.clearProperty('pactbroker.host')
    System.clearProperty('pactbroker.port')
    pactBrokerLoader = {
      new PactBrokerLoader(MinimalPactBrokerAnnotation.getAnnotation(PactBroker)) {
        @Override
        PactBrokerClient newPactBrokerClient(URI url, ValueResolver resolver) {
          assert url.host == 'my.pactbroker.host'
          assert url.port == 4711
          brokerClient
        }
      }
    }

    when:
    pactBrokerLoader().load('test')

    then:
    IllegalArgumentException exception = thrown(IllegalArgumentException)
    exception.message.startsWith('Invalid pact broker host specified')
  }

  @RestoreSystemProperties
  void 'Does not fail when no fallback port system properties is set'() {
    given:
    System.setProperty('pactbroker.host', 'my.pactbroker.host')
    System.clearProperty('pactbroker.port')
    pactBrokerLoader = {
      new PactBrokerLoader(MinimalPactBrokerAnnotation.getAnnotation(PactBroker)) {
        @Override
        PactBrokerClient newPactBrokerClient(URI url, ValueResolver resolver) {
          assert url.host == 'my.pactbroker.host'
          assert url.port == -1
          brokerClient
        }
      }
    }

    when:
    pactBrokerLoader().load('test')

    then:
    noExceptionThrown()
    1 * brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Right([])
  }

  def 'Loads pacts for each provided tag'() {
    given:
    tags = ['a', 'b', 'c']

    when:
    def result = pactBrokerLoader().load('test')

    then:
    1 * brokerClient.fetchConsumersWithSelectors('test', [new ConsumerVersionSelector('a', true)]) >> new Either.Right([new PactResult('test', 'a', '', [], []) ])
    1 * brokerClient.fetchConsumersWithSelectors('test', [new ConsumerVersionSelector('b', true)]) >> new Either.Right([ new PactResult('test', 'b', '', [], []) ])
    1 * brokerClient.fetchConsumersWithSelectors('test', [new ConsumerVersionSelector('c', true)]) >> new Either.Right([ new PactResult('test', 'c', '', [], []) ])
    0 * _
    result.size() == 3
  }

  def 'Loads latest pacts together with other tags'() {
    given:
    tags = ['a', 'latest', 'b']

    when:
    def result = pactBrokerLoader().load('test')

    then:
    1 * brokerClient.fetchConsumersWithSelectors('test', [new ConsumerVersionSelector('a', true)]) >> new Either.Right([ new PactResult('test', 'a', '', [], []) ])
    1 * brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Right([ new PactResult('test', 'latest', '', [], []) ])
    1 * brokerClient.fetchConsumersWithSelectors('test', [new ConsumerVersionSelector('b', true)]) >> new Either.Right([ new PactResult('test', 'b', '', [], []) ])
    0 * _
    result.size() == 3
  }

  @RestoreSystemProperties
  @SuppressWarnings('GStringExpressionWithinString')
  def 'Processes tags before pact load'() {
    given:
    System.setProperty('composite', "one${VALUES_SEPARATOR}two")
    tags = ['${composite}']

    when:
    def result = pactBrokerLoader().load('test')

    then:
    1 * brokerClient.fetchConsumersWithSelectors('test', [new ConsumerVersionSelector('one', true)]) >> new Either.Right([ new PactResult('test', 'one', '', [], []) ])
    1 * brokerClient.fetchConsumersWithSelectors('test', [new ConsumerVersionSelector('two', true)]) >> new Either.Right([ new PactResult('test', 'two', '', [], []) ])
    result.size() == 2
  }

  def 'Loads the latest pacts if no tag is provided'() {
    given:
    tags = []

    when:
    def result = pactBrokerLoader().load('test')

    then:
    result.size() == 1
    1 * brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Right([ new PactResult('test', 'latest', '', [], []) ])
  }

  @SuppressWarnings('GStringExpressionWithinString')
  def 'processes tags with the provided value resolver'() {
    given:
    tags = ['${a}', '${latest}', '${b}']
    def loader = pactBrokerLoader()
    loader.valueResolver = [resolveValue: { val -> 'X' } ] as ValueResolver

    when:
    def result = loader.load('test')

    then:
    3 * brokerClient.fetchConsumersWithSelectors('test', [new ConsumerVersionSelector('X', true)]) >> new Either.Right([ new PactResult('test', 'a', '', [], []) ])
    0 * _
    result.size() == 3
  }

  def 'Loads pacts only for provided consumers'() {
    given:
    consumers = ['a', 'b', 'c']

    when:
    def result = pactBrokerLoader().load('test')

    then:
    1 * brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Right([
            new PactResult('a', 'latest', '', [], []),
            new PactResult('b', 'latest', '', [], []),
            new PactResult('c', 'latest', '', [], []),
            new PactResult('d', 'latest', '', [], [])
    ])
    0 * _
    result.size() == 3
  }

  @RestoreSystemProperties
  @SuppressWarnings('GStringExpressionWithinString')
  def 'Processes consumers before pact load'() {
    given:
    System.setProperty('composite', "a${VALUES_SEPARATOR}b${VALUES_SEPARATOR}c")
    consumers = ['${composite}']

    when:
    def result = pactBrokerLoader().load('test')

    then:
    1 * brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Right([
            new PactResult('a', 'latest', '', [], []),
            new PactResult('b', 'latest', '', [], []),
            new PactResult('c', 'latest', '', [], []),
            new PactResult('d', 'latest', '', [], [])
    ])
    0 * _
    result.size() == 3
  }

  def 'Loads all consumer pacts if no consumer is provided'() {
    given:
    consumers = []

    when:
    def result = pactBrokerLoader().load('test')

    then:
    1 * brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Right([
            new PactResult('a', 'latest', '', [], []),
            new PactResult('b', 'latest', '', [], []),
            new PactResult('c', 'latest', '', [], []),
            new PactResult('d', 'latest', '', [], [])
    ])
    0 * _
    result.size() == 4
  }

  @RestoreSystemProperties
  @SuppressWarnings('GStringExpressionWithinString')
  def 'Loads all consumers by default'() {
    given:
    consumers = ['${pactbroker.consumers:}']

    when:
    def result = pactBrokerLoader().load('test')

    then:
    1 * brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Right([
      new PactResult('a', 'latest', '', [], []),
      new PactResult('b', 'latest', '', [], []),
      new PactResult('c', 'latest', '', [], []),
      new PactResult('d', 'latest', '', [], [])
    ])
    0 * _
    result.size() == 4
  }

  def 'Loads pacts only for provided consumers with the specified tags'() {
    given:
    consumers = ['a', 'b', 'c']
    tags = ['demo']

    when:
    def result = pactBrokerLoader().load('test')

    then:
    1 * brokerClient.fetchConsumersWithSelectors('test', [new ConsumerVersionSelector('demo', true)]) >> new Either.Right([
            new PactResult('a', 'demo', '', [], []),
            new PactResult('b', 'demo', '', [], []),
            new PactResult('c', 'demo', '', [], []),
            new PactResult('d', 'demo', '', [], [])
    ])
    0 * _
    result.size() == 3
  }

  def 'use the overridden pact URL'() {
    given:
    consumers = ['a', 'b', 'c']
    tags = ['demo']
    PactBrokerLoader loader = Spy(pactBrokerLoader())
    loader.overridePactUrl('http://overridden.com', 'overridden')
    def brokerUrlSource = new BrokerUrlSource('http://overridden.com', 'http://pactbroker:1234')
    def consumer = new ConsumerInfo('overridden', null, true, [], null, brokerUrlSource)

    when:
    def result = loader.load('test')

    then:
    1 * loader.loadPact(consumer, _) >> Stub(Pact)
    0 * brokerClient._
    result.size() == 1
  }

  def 'does not fail if the port is not provided'() {
    when:
    port = null
    pactBrokerLoader().load('test')

    then:
    1 * brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Right([])
    noExceptionThrown()
  }

  def 'configured from annotation with no port'() {
    given:
    pactBrokerLoader = {
      new PactBrokerLoader(PactBrokerAnnotationNoPort.getAnnotation(PactBroker)) {
        @Override
        PactBrokerClient newPactBrokerClient(URI url, ValueResolver resolver) {
          assert url.host == 'pactbroker.host'
          assert url.port == -1
          brokerClient
        }
      }
    }

    when:
    def result = pactBrokerLoader().load('test')

    then:
    result == []
    1 * brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Right([])
  }

  def 'configured from annotation with https and no port'() {
    given:
    pactBrokerLoader = {
      new PactBrokerLoader(PactBrokerAnnotationHttpsNoPort.getAnnotation(PactBroker)) {
        @Override
        PactBrokerClient newPactBrokerClient(URI url, ValueResolver resolver) {
          assert url.scheme == 'https'
          assert url.host == 'pactbroker.host'
          assert url.port == -1
          brokerClient
        }
      }
    }

    when:
    def result = pactBrokerLoader().load('test')

    then:
    result == []
    1 * brokerClient.fetchConsumersWithSelectors('test', []) >> new Either.Right([])
  }

  def 'Auth: Uses no auth if no auth is provided'() {
    given:
    pactBrokerLoader = {
      new PactBrokerLoader(PactBrokerAnnotationAuthNotSet.getAnnotation(PactBroker))
    }

    when:
    def pactBrokerClient = pactBrokerLoader()
            .newPactBrokerClient(new URI('http://localhost'), new SystemPropertyResolver())

    then:
    pactBrokerClient.options == [:]
  }

  def 'Auth: Uses basic auth if username and password are provided'() {
    given:
    pactBrokerLoader = {
      new PactBrokerLoader(PactBrokerAnnotationWithUsernameAndPassword.getAnnotation(PactBroker))
    }

    when:
    def pactBrokerClient = pactBrokerLoader()
            .newPactBrokerClient(new URI('http://localhost'), new SystemPropertyResolver())

    then:
    pactBrokerClient.options == ['authentication': ['basic', 'user', 'pw']]
  }

  def 'Auth: Uses basic auth if username and token are provided'() {
    given:
    pactBrokerLoader = {
      new PactBrokerLoader(PactBrokerAnnotationAuthWithUsernameAndToken.getAnnotation(PactBroker))
    }

    when:
    def pactBrokerClient = pactBrokerLoader()
            .newPactBrokerClient(new URI('http://localhost'), new SystemPropertyResolver())

    then:
    pactBrokerClient.options == ['authentication': ['basic', 'user', '']]
  }

  def 'Auth: Uses bearer auth if token is provided'() {
    given:
    pactBrokerLoader = {
      new PactBrokerLoader(PactBrokerAnnotationWithOnlyToken.getAnnotation(PactBroker))
    }

    when:
    def pactBrokerClient = pactBrokerLoader()
            .newPactBrokerClient(new URI('http://localhost'), new SystemPropertyResolver())

    then:
    pactBrokerClient.options == ['authentication': ['bearer', 'token-value']]
  }

  def 'Auth: Uses bearer auth if token and password are provided'() {
    given:
    pactBrokerLoader = {
      new PactBrokerLoader(PactBrokerAnnotationWithPasswordAndToken.getAnnotation(PactBroker))
    }

    when:
    def pactBrokerClient = pactBrokerLoader()
            .newPactBrokerClient(new URI('http://localhost'), new SystemPropertyResolver())

    then:
    pactBrokerClient.options == ['authentication': ['bearer', 'token-value']]
  }

  def 'Auth: No auth if neither token nor username is provided'() {
    given:
    pactBrokerLoader = {
      new PactBrokerLoader(PactBrokerAnnotationEmptyAuth.getAnnotation(PactBroker))
    }

    when:
    def pactBrokerClient = pactBrokerLoader().newPactBrokerClient(new URI('http://localhost'), new SystemPropertyResolver())

    then:
    pactBrokerClient.options == [:]
  }

  @PactBroker(host = 'pactbroker.host', port = '1000')
  static class FullPactBrokerAnnotation {

  }

  @PactBroker
  static class MinimalPactBrokerAnnotation {

  }

  @PactBroker(host = 'pactbroker.host')
  static class PactBrokerAnnotationNoPort {

  }

  @PactBroker(host = 'pactbroker.host', scheme = 'https')
  static class PactBrokerAnnotationHttpsNoPort {

  }

  @PactBroker(host = 'pactbroker.host')
  static class PactBrokerAnnotationAuthNotSet {

  }

  @PactBroker(host = 'pactbroker.host',
      authentication = @PactBrokerAuth(username = 'user', password =  'pw'))
  static class PactBrokerAnnotationWithUsernameAndPassword {

  }

  @PactBroker(host = 'pactbroker.host',
      authentication = @PactBrokerAuth(username = 'user', token = 'ignored'))
  static class PactBrokerAnnotationAuthWithUsernameAndToken {

  }

  @PactBroker(host = 'pactbroker.host',
      authentication = @PactBrokerAuth(password = 'pw', token = 'token-value'))
  static class PactBrokerAnnotationWithPasswordAndToken {

  }

  @PactBroker(host = 'pactbroker.host',
      authentication = @PactBrokerAuth(token = 'token-value'))
  static class PactBrokerAnnotationWithOnlyToken {

  }

  @PactBroker(host = 'pactbroker.host',
          authentication = @PactBrokerAuth)
  static class PactBrokerAnnotationEmptyAuth {

  }

}
