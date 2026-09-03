package dev.mkdev.portainerremote.data.backup

import android.util.Base64
import dev.mkdev.portainerremote.data.store.CustomLabel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Enveloppe d'une sauvegarde chiffree.
 *
 * Les parametres de derivation voyagent avec le fichier plutot que d'etre figes
 * dans le code : une sauvegarde ecrite aujourd'hui doit rester lisible par une
 * version future qui aurait durci ses reglages.
 */
@Serializable
data class BackupEnvelope(
    val format: String = FORMAT,
    val kdf: String = "PBKDF2WithHmacSHA256",
    val iterations: Int,
    val salt: String,
    val iv: String,
    val ciphertext: String,
) {
    companion object {
        const val FORMAT = "portainer-remote-backup-v1"
    }
}

/** Ce que la sauvegarde restaure : rien de plus que ce qu'on ne peut pas redecouvrir. */
@Serializable
data class BackupServer(
    val id: String,
    val label: String,
    val baseUrl: String,
    val authMode: String,
    val username: String = "",
    /**
     * En clair *dans le JSON*, qui n'existe jamais que chiffre. Le secret
     * d'origine est scelle par le Keystore, dont la cle ne quitte pas
     * l'appareil et disparait a la desinstallation : la rechiffrer telle quelle
     * produirait une sauvegarde illisible, c'est-a-dire inutile.
     */
    val secret: String = "",
)

@Serializable
data class BackupFavorite(
    val serverId: String,
    val serverLabel: String,
    val stackKey: String,
    val name: String,
    val envId: Int,
)

@Serializable
data class BackupPayload(
    val exportedAt: Long,
    val appVersion: String,
    val servers: List<BackupServer>,
    val favorites: List<BackupFavorite>,
    /**
     * Ports de raccourci choisis a la main. Valeur par defaut : une sauvegarde
     * ecrite avant l'apparition du reglage doit rester importable.
     */
    val pinnedPorts: Map<String, Int> = emptyMap(),
    /** Conteneurs favoris, par clef serveur|environnement|nom. */
    val favoriteContainers: List<String> = emptyList(),
    /** Mode d'affichage de l'onglet Favoris. Vide : laisser le defaut. */
    val favoritesView: String = "",
    /** Noms et descriptions personnalises, par clef typee. */
    val labels: Map<String, CustomLabel> = emptyMap(),
)

class WrongPassphraseException : Exception("Phrase de passe incorrecte, ou fichier abîmé.")

class UnknownFormatException(format: String) :
    Exception("Fichier non reconnu : « $format ».")

/**
 * Chiffrement d'une sauvegarde par phrase de passe.
 *
 * AES-256-GCM, cle derivee en PBKDF2-HMAC-SHA256. GCM authentifie le message :
 * une mauvaise phrase de passe ne produit pas un dechiffrement silencieusement
 * faux, elle leve une erreur.
 */
object BackupCrypto {

    /**
     * Recommandation OWASP pour PBKDF2-HMAC-SHA256. Cout mesure : de l'ordre de
     * la seconde sur un telephone milieu de gamme, paye une fois a l'export et
     * une fois a l'import.
     */
    const val ITERATIONS = 210_000

    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** 12 caracteres : une sauvegarde porte tous les jetons a la fois. */
    const val MIN_PASSPHRASE = 12

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val random = SecureRandom()

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            // Efface la copie interne de la phrase de passe.
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    fun encrypt(payload: BackupPayload, passphrase: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase.toCharArray(), salt, ITERATIONS)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val sealed = cipher.doFinal(json.encodeToString(payload).toByteArray(Charsets.UTF_8))

        return json.encodeToString(
            BackupEnvelope(
                iterations = ITERATIONS,
                salt = encode(salt),
                iv = encode(iv),
                ciphertext = encode(sealed),
            ),
        )
    }

    fun decrypt(fileContent: String, passphrase: String): BackupPayload {
        val envelope = try {
            json.decodeFromString<BackupEnvelope>(fileContent)
        } catch (e: Exception) {
            throw UnknownFormatException("illisible")
        }

        if (envelope.format != BackupEnvelope.FORMAT) {
            throw UnknownFormatException(envelope.format)
        }

        val key = deriveKey(passphrase.toCharArray(), decode(envelope.salt), envelope.iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, decode(envelope.iv)))

        val plain = try {
            cipher.doFinal(decode(envelope.ciphertext))
        } catch (e: Exception) {
            // GCM authentifie : l'echec du tag est la seule reponse honnete.
            throw WrongPassphraseException()
        }

        return try {
            json.decodeFromString<BackupPayload>(plain.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw UnknownFormatException("contenu inattendu")
        }
    }
}
