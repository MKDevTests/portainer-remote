# Portainer Remote

Application Android pour piloter des stacks et des conteneurs Docker via
l'API [Portainer](https://www.portainer.io/) : démarrer, arrêter, relancer,
redéployer avec les images à jour, et faire le ménage dans les images.

Pensée pour un usage personnel sur un NAS joint par VPN, mais **conçue pour
fonctionner avec n'importe quelle instance Portainer**, pas seulement celle de
son auteur.

## État

Utilisable. Tout est testé sur un appareil réel, en géométrie téléphone et en
géométrie tablette. Seul l'écran de logs reste à faire.

| Fonctionnalité | État |
| --- | --- |
| Plusieurs serveurs Portainer | fait |
| Authentification par jeton ou par mot de passe | fait |
| Liste des stacks et des conteneurs | fait |
| Démarrer / arrêter / relancer | fait |
| Redéployer avec images à jour | fait |
| Gestion des images, suppression des inutilisées | fait |
| Recherche, tri et filtre par état | fait |
| Mise en page téléphone et tablette | fait |
| Widget et tuile Quick Settings | fait |
| Mise à jour depuis l'app (téléchargement et installation) | fait |
| Consultation des logs | démultiplexeur écrit, écran à faire |

### Mises à jour

L'écran **Mises à jour**, accessible depuis la barre de l'écran des serveurs,
affiche la version installée, vérifie à la demande la dernière release du dépôt
nommé par `UPDATE_REPO` dans `app/build.gradle.kts`, télécharge l'APK et le
passe à l'installateur d'Android. Une bannière apparaît aussi d'elle-même sur
l'écran des serveurs quand une version plus récente existe.

Un fork ne change que la ligne `UPDATE_REPO` : rien d'autre dans le code ne
nomme le dépôt.

Trois garde-fous, parce que `REQUEST_INSTALL_PACKAGES` n'est pas une permission
anodine pour une application de pilotage d'infrastructure :

- elle donne le droit de *demander*, pas d'installer : Android affiche son
  propre écran de confirmation, que rien ici ne contourne ;
- l'autorisation « installer depuis cette source » est un réglage système
  distinct, révocable, que l'application ne peut que proposer d'ouvrir ;
- Android refuse tout APK signé par une autre clé que celle de la version
  installée. C'est cette vérification, et non l'application, qui protège contre
  un binaire substitué.

L'APK est déposé dans le cache, dans le seul dossier exposé par le
`FileProvider`, et le dossier est vidé avant chaque téléchargement.

## Cinq règles de conception

L'application ne suppose jamais rien de l'instance à laquelle elle parle.

1. **Aucun identifiant en dur.** Une seule saisie : l'URL de base. Les
   `endpointId` et identifiants de stack sont découverts à l'exécution.
2. **Sonder, pas versionner.** Aucune branche sur un numéro de version : on
   tente la route, et un 404 ou 501 signifie « non supporté ici ».
3. **Parsing tolérant.** `ignoreUnknownKeys`, valeurs par défaut partout hors
   du noyau. Portainer ajoute et retire des champs entre versions mineures.
4. **Assumer l'hétérogénéité.** Docker, agent, Swarm, Kubernetes et Edge
   cohabitent. Ce qui n'est pas pilotable s'affiche avec sa raison.
5. **Valider sur deux versions.** Développée contre Portainer 2.19.4 et
   2.31.3 simultanément.

## Ce que la mesure a appris

Quatre constats non documentés, tous obtenus en sondant de vraies instances.
Ils expliquent l'essentiel de l'architecture.

- **`/api/stacks` ne voit qu'une partie des stacks.** Sur une installation
  réelle, elle en a renvoyé un sur quatre : seuls y figurent ceux créés dans
  Portainer. Les autres n'existent que sous forme d'étiquettes
  `com.docker.compose.project` sur leurs conteneurs. L'application fusionne les
  deux sources, comme le fait l'interface web.
- **`/api/endpoints` pèse 938 Ko.** Elle embarque un instantané complet des
  conteneurs, volumes et images. `?excludeSnapshots=true` la ramène à 3,5 Ko,
  soit un rapport de 264 pour 1. L'instantané n'est jamais utilisé : il est
  périodique, donc périmé.
- **Le code HTTP ne dit pas si l'action a réussi.** Arrêter un stack déjà
  arrêté renvoie 400 ; démarrer un conteneur déjà en marche renvoie 304. Deux
  façons de dire « c'était déjà fait », l'une classée erreur. Après chaque
  action, l'application relit l'état et le compare à l'intention.
- **`/api/system/status` répond sans authentification.** Un test de connexion
  fondé sur elle annonce « connexion établie » avec un jeton invalide. Le test
  interroge donc `/api/endpoints`.

Les logs Docker arrivent par ailleurs en flux multiplexé, chaque trame précédée
d'un en-tête binaire de 8 octets — sauf pour un conteneur doté d'un TTY.

## Compiler

Nécessite un JDK 17 ou plus et le SDK Android avec la plateforme 36.

```bash
./gradlew assembleDebug
```

L'APK est produit dans `app/build/outputs/apk/debug/`.

Les versions ne sont pas choisies au hasard et sont liées entre elles : AGP
8.13.2 parce qu'AGP 9 rejette le plugin `kotlin.android` ; Gradle 8.13 imposé
par AGP ; `compileSdk` 36 ; Compose BOM 2025.10.01 parce que les versions 2026
exigent `compileSdk` 37. Mettre à jour l'un impose de refaire toute la chaîne.

### Signer une version release

```powershell
powershell -ExecutionPolicy Bypass -File signing\new-keystore.ps1
```

Le script génère le keystore dans `~/.android-keys`, hors du dépôt, écrit
`keystore.properties` — déjà couvert par `.gitignore` — et refuse d'écraser une
clé existante. Les mots de passe sont saisis au clavier, jamais affichés et
jamais passés en argument, où ils seraient visibles dans la liste des
processus : `keytool` les lit dans des variables d'environnement effacées
ensuite.

```bash
./gradlew assembleRelease
```

Sans `keystore.properties`, la commande réussit quand même et produit un
`app-release-unsigned.apk` : le dépôt reste compilable par quelqu'un qui ne
détient pas la clé.

Cette clé n'a aucune sauvegarde ailleurs. Android refuse une mise à jour signée
par une clé différente de celle de la version installée : la perdre oblige à
désinstaller l'application et à reconfigurer tous les serveurs.

### Si la résolution des dépendances échoue en `PKIX path building failed`

Un antivirus qui inspecte le HTTPS (Avast, Kaspersky, ESET…) re-signe le trafic
avec sa propre autorité. Elle est connue du magasin Windows, mais pas du
`cacerts` de la JVM. Gradle annonce alors « plugin not found », ce qui n'a rien
à voir avec la cause réelle.

Le projet contourne le problème dans `gradle.properties` en faisant lire le
magasin Windows à Java. Le lanceur du wrapper, lui, ne lit pas ce fichier :

```bash
export GRADLE_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

## Sécurité

- Le jeton ou le mot de passe est chiffré en AES-GCM par une clé du **Keystore
  Android**, qui ne quitte jamais l'appareil.
- Le JWT du mode mot de passe vit en mémoire et disparaît avec le processus.
- Le trafic en clair est autorisé — beaucoup de Portainer d'infrastructure
  privée n'ont qu'un HTTP local — mais l'écran de configuration **avertit
  explicitement** dès qu'une adresse `http://` non locale est saisie.
- Les scripts de sondage demandent le jeton au clavier et ne l'écrivent jamais,
  ni en argument, ni en fichier, ni dans l'historique du terminal.

## Outils

`probe/probe.ps1` interroge une instance en lecture seule et archive les
réponses JSON, en comparant au passage le poids avec et sans
`excludeSnapshots`. `probe/probe-actions.ps1` valide les routes d'action et le
format des logs ; il arrête puis redémarre un stack, et refuse donc de
s'exécuter ailleurs que sur `localhost` sans `-Force` explicite.

## Licence

MIT.
