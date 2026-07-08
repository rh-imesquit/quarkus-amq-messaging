# Como definir permissões RBAC read-only usando Jolokia

## Objetivo

Configurar o AMQ Broker 7.12 no OpenShift para permitir que um usuário, por exemplo `rouser`, consiga executar operações de leitura via API Jolokia, mas não consiga executar operações administrativas de escrita, como criação de address, criação de queue, alteração ou remoção de recursos.

O comportamento para validar nesse laboratório será:

| Usuário | Operação                               | Resultado esperado |
|---------|----------------------------------------|--------------------|
| rouser  | Listar filas de fila.teste via Jolokia | Permitido          |
| rouser  | Criar nova fila via Jolokia            | Negado com 403     |
| admin   | Listar filas via Jolokia               | Permitido          |
| admin   | Criar nova fila via Jolokia            | Permitido          |

O erro esperado para o usuário `rouser` em operações de escrita é semelhante a:


*AMQ229032: User: rouser does not have permission='EDIT' on address mops.broker.createQueue.*

Esse erro confirma que o RBAC administrativo do Artemis está sendo aplicado corretamente para chamadas realizadas via Jolokia/JMX.

## Requisitos e tecnologias usadas nesse tutorial

| Component                                   | Version |
|---------------------------------------------|---------|
| Red Hat OpenShift Container Platform        | 4.20    |
| Red Hat AMQ Broker                          | 7.12    |


Como requisito para a execução desse laboratório, é necessário que o Operator do AMQ Broker já esteja instalado no OpenShift.

Caso ainda não tenha sido feita a instalação, consulte a documentação oficial de instalação do AMQ Broker Operator no OpenShift:

[How to install AMQ Broker Operator](./How%20to%20install%20AMQ%20Broker%20Operator%20on%20OpenShift.md)

Também é recomendado ter:

- acesso ao `oc` cli;
- `curl` e `jq` instalados na estação de trabalho;


# Montando o ambiente

## 1. Criação do namespace para o laboratório

```bash
oc new-project activemq
```


## 2. Criação da Secret `amq-broker-jaas-config`

Vamos aplicar a Secret responsável por armazenar as informações de autenticação que serão usadas pelo AMQ Broker. Você pode ver o conteúdo desse arquivo [aqui](../infra/amq-rbac/01-amq-broker-jaas-config.yaml).

```bash
oc apply -f infra/amq-rbac/01-amq-broker-jaas-config.yaml
```

É aqui que você define quem pode autenticar no broker e quais roles esses usuários recebem. Sem essa Secret, o broker usaria apenas os usuários e roles padrão gerados internamente pelo Operator.

Essa Secret contém três arquivos importantes para o broker:

- `login.config`: informa ao AMQ Broker como ele deve autenticar os usuários, neste caso usando `PropertiesLoginModule`;
- `users.properties`: define os usuários e suas senhas;
- `roles.properties`: associa usuários às roles.

No cenário deste laboratório, a ideia é criar dois usuários com seus respectivos perfis de acesso:

- admin  -> role admins
- rouser -> role leitores

A role `admins` será usada para operações administrativas, incluindo leitura e escrita via Jolokia. Por sua vez, a role `leitores` será usada para operações de leitura via Jolokia, sem permissão de escrita.

> **Atenção:** é necessário manter o módulo padrão no `login.config`, pois ele preserva o funcionamento interno do broker e do Operator. O Operator pode usar credenciais internas para executar operações de gerenciamento e reconciliação. Se você remover completamente o módulo default, existe risco de quebrar a comunicação interna entre Operator e broker.


## 3. Criação do broker com o CR `ActiveMQArtemis`

Esse é o arquivo principal da instância do AMQ Broker. Ele define como o broker será criado no OpenShift, quais configurações serão aplicadas pelo Operator e quais permissões cada role terá. Você pode ver o conteúdo desse arquivo [aqui](../infra/amq-rbac/02-amq-broker.yaml).

```bash
oc apply -f infra/amq-rbac/02-amq-broker.yaml
```

É no `ActiveMQArtemis` que configuramos:

- quantidade de brokers;
- persistência;
- storage;
- console;
- montagem da Secret JAAS;
- permissões de mensageria;
- permissões de Jolokia/JMX;
- endereços e filas;
- ajustes no `management.xml`.

Vamos focar nos blocos necessários para a execução do nosso laboratório.

O bloco `extraMounts.secrets` monta a Secret JAAS dentro do pod do broker. Sem esse bloco, a Secret existiria no namespace, mas o broker não usaria os usuários `admin` e `rouser`.

```yaml
extraMounts:
  secrets:
    - "amq-broker-jaas-config"
```
> **JAAS (Java Authentication and Authorization Service):** é a sigla em inglês para o Serviço de Autenticação e Autorização do Java. Trata-se de uma API e extensão padrão da plataforma Java, projetada para garantir a segurança dos sistemas ao verificar quem está acessando a aplicação (autenticação) e o que essa pessoa tem permissão para fazer (autorização).

Outro bloco importante é o `env`, com a variável de ambiente `JAVA_ARGS_APPEND`, pois ele é essencial para fazer o RBAC de Jolokia funcionar.

```yaml
env:
  - name: JAVA_ARGS_APPEND
    value: "-Dhawtio.role=* -Djavax.management.builder.initial=org.apache.activemq.artemis.core.server.management.ArtemisRbacMBeanServerBuilder"
```

A opção `-Dhawtio.role=*` permite que usuários autenticados no JAAS consigam acessar a camada Hawtio/Jolokia.

Sem isso, o Hawtio pode exigir uma role específica, normalmente `admin`, e bloquear usuários como `rouser`, mesmo que eles estejam corretamente cadastrados no JAAS.

> **Jolokia:** é uma tecnologia de monitoramento Java que atua como uma ponte remota, transformando o ecossistema complexo do JMX (Java Management Extensions) em uma API HTTP/JSON leve e de fácil acesso. Ele permite que agentes e ferramentas externas interajam com a JVM, coletando métricas e executando operações de gerenciamento de forma simplificada, segura e compatível com firewalls, sem a necessidade de conexões RMI pesadas.

> **Hawtio:** é a Console Web usada pelo AMQ Broker. Ela se comunica com o broker por meio de chamadas HTTP para o Jolokia, que por sua vez executa operações JMX/MBeans no broker.

A opção `-Djavax.management.builder.initial=org.apache.activemq.artemis.core.server.management.ArtemisRbacMBeanServerBuilder` é responsável por ativar o RBAC administrativo baseado em MBeans. Sem essa configuração, as regras `mops.#` podem não ser aplicadas corretamente para chamadas de gerenciamento via Jolokia/JMX.

> **JMX (Java Management Extensions):** é a arquitetura e o padrão nativo da plataforma Java projetado para monitorar, gerenciar e expor o comportamento de aplicações e da própria máquina virtual (JVM).

> **MBeans (Managed Beans):** são objetos Java especiais que representam recursos gerenciáveis, como o uso de memória, serviços ativos ou configurações de uma aplicação, dentro da plataforma JMX (Java Management Extensions).

O `brokerProperties` é o bloco mais importante do arquivo. Ele substitui a necessidade de alterar manualmente o `broker.xml` e também evita o uso das CRDs deprecated, como `ActiveMQArtemisAddress` e `ActiveMQArtemisSecurity`.

É nele que definimos:

- endereços;
- filas;
- permissões de mensageria;
- permissões de gerenciamento/Jolokia.

```yaml
brokerProperties:
  - 'addressConfigurations."fila.teste".routingTypes=ANYCAST'
  - 'addressConfigurations."fila.teste".queueConfigs."fila.teste".address=fila.teste'
  - 'addressConfigurations."fila.teste".queueConfigs."fila.teste".routingType=ANYCAST'
  - "securityRoles.#.admins.createAddress=true"
  - "securityRoles.#.admins.deleteAddress=true"
  ...
  - "securityRoles.#.leitores.consume=true"
  - "securityRoles.#.leitores.browse=true"
  ...
  - 'securityRoles."mops.#".admins.view=true'
  - 'securityRoles."mops.#".admins.edit=true'
  - 'securityRoles."mops.#".leitores.view=true'
```

A propriedade `addressConfigurations` substitui o antigo uso do recurso `ActiveMQArtemisAddress`.

Já as propriedades `securityRoles` têm dois usos diferentes, que podem causar confusão se não forem bem separados:

1. RBAC da camada de mensageria;
2. RBAC da camada de gerenciamento via Jolokia/JMX.

As permissões de RBAC de mensageria abaixo controlam acesso via protocolos de mensageria, como CORE, JMS e AMQP:

```yaml
- "securityRoles.#.leitores.consume=true"
- "securityRoles.#.leitores.browse=true"
```

Essas permissões permitem que o usuário associado à role `leitores` possa consumir ou navegar mensagens em filas que correspondam ao wildcard `#`.

Essa camada controla ações como:

- consumir mensagens;
- navegar mensagens;
- enviar mensagens;
- criar address;
- criar queue;
- gerenciar recursos via protocolo de mensageria.

No caso do usuário `rouser`, a recomendação é conceder apenas leitura/navegação na camada de mensageria, sem conceder permissões como `send`, `createAddress`, `createDurableQueue`, `createNonDurableQueue` ou `manage`.

Por outro lado, para chamadas feitas via Jolokia, a configuração mais importante é a baseada em `mops.#`:

```yaml
- 'securityRoles."mops.#".admins.view=true'
- 'securityRoles."mops.#".admins.edit=true'
- 'securityRoles."mops.#".leitores.view=true'
```

Essa camada controla operações administrativas expostas por MBeans, como:

- listar addresses;
- listar queues;
- consultar atributos do broker;
- criar queues;
- criar addresses;
- remover ou alterar recursos.

A permissão `view=true` permite operações de leitura.  
A permissão `edit=true` permite operações administrativas de escrita.

Além de configurar `mops.#`, é necessário garantir que o RBAC default do `management.xml` não sobreponha ou interfira no modelo definido no `brokerProperties`. Por isso, o laboratório também aplica um ajuste no `management.xml`, deixando-o vazio:

```xml
<management-context xmlns="http://activemq.apache.org/schema" />
```

Esse ajuste é feito via `resourceTemplates`, alterando o init container do StatefulSet gerado pelo Operator.

Esse ponto é importante porque o `management.xml` default pode conter regras próprias de acesso administrativo. Ao esvaziá-lo, o broker passa a considerar o RBAC de gerenciamento definido no `brokerProperties`, por meio das regras `mops.#`.

## 4. Criação da rota customizada para acesso à Console/Jolokia

A Route customizada expõe externamente o endpoint HTTP da Console/Jolokia. No nosso cenário, foi criada uma Route HTTPS com terminação TLS Edge apontando para o Service do broker. Você pode ver o conteúdo desse arquivo [aqui](../infra/amq-rbac/03-amq-broker-console-route.yaml).

```bash
oc apply -f infra/amq-rbac/03-amq-broker-console-route.yaml
```

A Route não controla permissões de leitura ou escrita. Ela apenas expõe o caminho HTTP. A autorização continua sendo feita pelo AMQ Broker.

O fluxo fica assim:

![Fluxo de acesso ao AMQ Broker](/images/amq-rbac/01-amq-broker-access-flow.png)

A autenticação é feita pelo JAAS e a autorização das operações Jolokia/JMX é feita pelas regras `mops.#`.

# Validando os cenários

Defina a variável com o host da Route:

```bash
ROUTE=$(oc get route amq-broker-console-route -n activemq -o jsonpath='{.spec.host}')
echo $ROUTE
```

## 1. Leitura com usuário `rouser`: listar filas do address `fila.teste`

```bash
curl -sk -u rouser:ro123 \
  -H "Content-Type: application/json" \
  -X POST "https://${ROUTE}/console/jolokia/" \
  -d '{
    "type": "read",
    "mbean": "org.apache.activemq.artemis:broker=\"amq-broker\",component=addresses,address=\"fila.teste\"",
    "attribute": "QueueNames"
  }' | jq
```

Resultado esperado:

![Usuário rouser com permissão de leitura](/images/amq-rbac/02-rouser-list.png)

O usuário `rouser` deve conseguir listar as filas associadas ao address `fila.teste`.

## 2. Escrita com usuário `rouser`: tentar criar uma nova fila no address `fila.teste`

```bash
curl -sk -u rouser:ro123 \
  -H "Content-Type: application/json" \
  -X POST "https://${ROUTE}/console/jolokia/" \
  -d '{
    "type": "exec",
    "mbean": "org.apache.activemq.artemis:broker=\"amq-broker\"",
    "operation": "createQueue(java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean,int,boolean,boolean)",
    "arguments": [ "fila.teste", "ANYCAST", "fila.teste.rouser.nova", null, true, -1, false, false]
  }' | jq
```

Resultado esperado:

![Usuário rouser sem permissão de escrita](/images/amq-rbac/03-rouser-create.png)

O usuário `rouser` não deve conseguir criar a fila, pois ele não possui permissão `edit=true` na camada `mops.#`.

## 3. Escrita com usuário `admin`: tentar criar uma nova fila no address `fila.teste`

```bash
curl -sk -u admin:admin123 \
  -H "Content-Type: application/json" \
  -X POST "https://${ROUTE}/console/jolokia/" \
  -d '{
    "type": "exec",
    "mbean": "org.apache.activemq.artemis:broker=\"amq-broker\"",
    "operation": "createQueue(java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean,int,boolean,boolean)",
    "arguments": [ "fila.teste", "ANYCAST", "fila.teste.admin", null, true, -1, false, false]
  }' | jq
```

Resultado esperado:

![Usuário admin com permissão de escrita](/images/amq-rbac/04-admin-create.png)

O usuário `admin` deve conseguir criar a fila, pois possui permissões `view=true` e `edit=true` na camada `mops.#`.

## 4. Leitura com usuário `admin`: listar filas do address `fila.teste`

```bash
curl -sk -u admin:admin123 \
  -H "Content-Type: application/json" \
  -X POST "https://${ROUTE}/console/jolokia/" \
  -d '{
    "type": "read",
    "mbean": "org.apache.activemq.artemis:broker=\"amq-broker\",component=addresses,address=\"fila.teste\"",
    "attribute": "QueueNames"
  }' | jq
```

Resultado esperado:

![Usuário admin com permissão de leitura](/images/amq-rbac/05-admin-list.png)

O usuário `admin` deve conseguir listar as filas associadas ao address `fila.teste`.

Bom trabalho, nós completamos nosso laboratório!

# Conclusão

A execução deste laboratório demonstrou que o controle de acesso read-only para usuários no AMQ Broker via Jolokia deve ser configurado na camada de gerenciamento/JMX, utilizando RBAC com mops.#. A autenticação dos usuários foi realizada via JAAS, enquanto a autorização das operações foi definida no brokerProperties, permitindo que o usuário rouser executasse consultas com view=true, mas bloqueando operações de escrita por não possuir edit=true. Dessa forma, foi validado que é possível expor a API Jolokia para usuários com acesso restrito, mantendo operações administrativas disponíveis apenas para usuários autorizados, como admin.

# Documentações e links de referência

- [Red Hat AMQ Broker 7.12 Documentation](https://docs.redhat.com/en/documentation/red_hat_amq_broker/7.12)
- [Red Hat AMQ Broker 7.12 Documentation - Deprecated features](https://docs.redhat.com/en/documentation/red_hat_amq_broker/7.12/html/release_notes_for_red_hat_amq_broker_7.12/deprecated_features)
- [Red Hat AMQ Broker 7.12 Documentation - Configuring Operator-based broker deployments](https://docs.redhat.com/en/documentation/red_hat_amq_broker/7.12/html/deploying_amq_broker_on_openshift/assembly-br-configuring-operator-based-deployments_broker-ocp)
- [Red Hat AMQ Broker 7.12 Documentation - Securing brokers](https://docs.redhat.com/en/documentation/red_hat_amq_broker/7.12/html/configuring_amq_broker/assembly-br-securing-brokers_configuring)
- [Apache ActiveMQ 2.33 Documentation - Management Console](https://artemis.apache.org/components/artemis/documentation/previous/2.33.0/management-console.html#management-console)
- [Apache ActiveMQ 2.33 Documentation - Authentication & Authorization](https://artemis.apache.org/components/artemis/documentation/previous/2.33.0/security.html#authentication-authorization)

