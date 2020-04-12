package io.armory.plugin.events.listener.github

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.netflix.spinnaker.echo.api.events.Event
import com.netflix.spinnaker.echo.api.events.EventListener
import io.jsonwebtoken.JwtBuilder
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import okhttp3.OkHttpClient
import org.kohsuke.github.*
import org.pf4j.Extension
import org.pf4j.Plugin
import org.pf4j.PluginWrapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.security.Key
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*


class GitHubEventListenerPlugin(wrapper: PluginWrapper) : Plugin(wrapper) {
    private val logger = LoggerFactory.getLogger(GitHubEventListenerPlugin::class.java)

    override fun start() {
        logger.info("GitHubEventListenerPlugin.start()")
    }

    override fun stop() {
        logger.info("GitHubEventListenerPlugin.stop()")
    }
}

//App ID: 60608
//Client ID: Iv1.a6c7fd45cd722e29
//Client secret: 96fd974a443244bd0012d43b4aadefbe3897c4dd

data class GitHubEvent(
        val sha: String,
        val branch: String,
        val deploymentMessage: String)

@Extension
open class GitHubEventListener(val config: GitHubEventListenerConfig) : EventListener {
    private val logger = LoggerFactory.getLogger(GitHubEventListener::class.java)

    private val mapper = jacksonObjectMapper()

    protected open fun getHttpClient() : OkHttpClient {
        return OkHttpClient()
    }

    protected open fun getLogger() : Logger {
        return logger
    }

    private fun getPrivateKey() : PrivateKey {
        val file = File(javaClass.classLoader.getResource("spinnaker-event-plugin.2020-04-11.private-key.der").file)
        val keyBytes = Files.readAllBytes(file.toPath())
        val spec = PKCS8EncodedKeySpec(keyBytes)
        val kf: KeyFactory = KeyFactory.getInstance("RSA")
        return kf.generatePrivate(spec)
    }

    @Throws(Exception::class)
    open fun createJWT(
            githubAppId: String?,
            ttlMillis: Long): String? {
        //The JWT signature algorithm we will be using to sign the token
        val signatureAlgorithm: SignatureAlgorithm = SignatureAlgorithm.RS256
        val nowMillis = System.currentTimeMillis()
        val now = Date(nowMillis)
        //We will sign our JWT with our private key
        val signingKey: Key = getPrivateKey()
        //Let's set the JWT Claims
        val builder: JwtBuilder = Jwts.builder()
                .setIssuedAt(now)
                .setIssuer(githubAppId)
                .signWith(signingKey, signatureAlgorithm)
        //if it has been specified, let's add the expiration
        if (ttlMillis > 0) {
            val expMillis = nowMillis + ttlMillis
            val exp = Date(expMillis)
            builder.setExpiration(exp)
        }
        //Builds the JWT and serializes it to a compact, URL-safe string
        return builder.compact()
    }

    override fun processEvent(event: Event) {
        var eventName = event.details.type;
        if (eventName.startsWith("orca:stage:starting") && event.content["name"].toString().startsWith("Deploy")) {
            val jwtToken = createJWT("60608", 600000)
            val gitHubApp = GitHubBuilder().withJwtToken(jwtToken).build()
            val installation = gitHubApp.app.getInstallationByOrganization("kubikl")
            val appInstallationToken = installation.createToken().create()
            val root = GitHubBuilder()
                    .withAppInstallationToken(appInstallationToken.token)
                    .build()
            val repository = root.getRepository("kubikl/realworld-backend")
//            val issue = repository.createIssue(event.content["name"].toString()).body((event.content["context"] as LinkedHashMap<String, String>)["cloudProvider"].toString())
//            issue.label(event.details.application)
//            issue.create()
            val deployment = GHDeploymentBuilder(repository)
            deployment.autoMerge(false)
            deployment.description(event.details.application)
            deployment.environment("dev")
            deployment.ref("my-branch")
            deployment.task(event.content["name"].toString())
            deployment.description(event.rawContent)
            deployment.create()
            logger.info("Stage Type :: ${event.content["name"]}")
            logger.info("Cloud Provider :: ${(event.content["context"] as LinkedHashMap<String, String>)["cloudProvider"]}")
            logger.info("Deploying :: ${event.details.application}")
        }

        if (eventName.startsWith("orca:stage:complete")  && event.content["name"].toString().startsWith("Deploy")) {
            val jwtToken = createJWT("60608", 600000)
            val gitHubApp = GitHubBuilder().withJwtToken(jwtToken).build()
            val installation = gitHubApp.app.getInstallationByOrganization("kubikl")
            val appInstallationToken = installation.createToken().create()
            val root = GitHubBuilder()
                    .withAppInstallationToken(appInstallationToken.token)
                    .build()
            val repository = root.getRepository("kubikl/realworld-backend")
            val deployments = repository.listDeployments("", "my-branch", event.content["name"].toString(), "dev")
            var deployment = repository.getDeployment(deployments.toArray()[0].id)
            var status = deployment.createStatus(GHDeploymentState.SUCCESS)
            status.description(event.rawContent)
            status.targetUrl("http://spinnaker.dev/output/deploy")
            status.create()

            logger.info("Stage Type :: ${event.content["name"]}")
            logger.info("Cloud Provider :: ${(event.content["context"] as LinkedHashMap<String, String>)["cloudProvider"]}")
            logger.info("Deploying :: ${event.details.application}")
        }

    }
}

