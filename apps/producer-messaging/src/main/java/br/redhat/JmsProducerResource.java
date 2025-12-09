package br.redhat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSProducer;
import jakarta.jms.Queue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Path("/jms")
@ApplicationScoped
public class JmsProducerResource {

    private static final Logger LOG = Logger.getLogger(JmsProducerResource.class);

    @Inject
    ConnectionFactory connectionFactory;

    @ConfigProperty(name = "jms.demo.queue-name")
    String queueName;

    @POST
    @Path("/send")
    @Produces(MediaType.TEXT_PLAIN)
    public Response sendMessages() {
        LOG.infof("Enviando 10 mensagens JMS para a fila '%s'...", queueName);

        try (JMSContext context = connectionFactory.createContext()) {
            Queue queue = context.createQueue(queueName);
            JMSProducer producer = context.createProducer();

            for (int i = 1; i <= 10; i++) {
                String body = "Mensagem JMS " + i;
                producer.send(queue, body);
                LOG.infof("Mensagem enviada: %s", body);
            }
        } catch (Exception e) {
            LOG.error("Erro ao enviar mensagens JMS", e);
            return Response.serverError()
                    .entity("Erro ao enviar mensagens: " + e.getMessage())
                    .build();
        }

        return Response.ok("10 mensagens enviadas para a fila " + queueName).build();
    }
}
