# Portainer Remote

Application Android pour piloter des stacks et des conteneurs Docker via
l'API [Portainer](https://www.portainer.io/) : démarrer, arrêter, relancer,
redéployer avec les images à jour, et faire le ménage dans les images.

Pensée pour un usage personnel sur un NAS joint par VPN, mais **conçue pour
fonctionner avec n'importe quelle instance Portainer**, pas seulement celle de
son auteur.

## État

Utilisable et complet pour l'usage visé. Tout est testé sur un appareil réel,
en géométrie téléphone et en géométrie tablette.

| Fonctionnalité | État |
| --- | --- |
| Plusieurs serveurs Portainer | fait |
| Authentification par jeton ou par mot de passe | fait |
| Liste des stacks et des conteneurs | fait |
| Démarrer / arrêter / relancer | fait |
| Redéployer avec images à jour | fait |
| Gestion des images, filtre inutilisées / sans étiquette | fait |
| Onglet Stacks et onglet Conteneurs à plat | fait |
| Recherche, tri et filtre par état | fait |
| Mise en page téléphone et tablette | fait |
| Widget et tuile Quick Settings | fait |
| Mise à jour depuis l'app (téléchargement et installation) | fait |
| Notification et vérification quotidienne des releases | fait |
| Sauvegarde chiffrée export / import | fait |
| Consultation des logs des conteneurs | fait |
| Ports publiés et raccourci vers l'interface web | fait |
| Choix manuel du port de raccourci | fait |
| Onglet Favoris, en raccourcis ou en détaillé | fait |
| Noms personnalisés et descriptions | fait |

### Mises à jour

L'écran **Mises à jour**, accessible depuis la barre de l'écran des serveurs,
affiche la version installée, vérifie à la demande la dernière release du dépôt
nommé par `UPDATE_REPO` dans `app/build.gradle.kts`, télécharge l'APK et le
passe à l'installateur d'Android.

Trois signaux, du plus discret au plus insistant : une **pastille** sur l'icône
de la barre, une **bannière** sur l'écran des serveurs, et une **notification**
Android. La vérification a lieu à l'ouverture de l'application et **une fois par
jour** via WorkManager, donc même sans l'ouvrir. Une même version n'est notifiée
qu'une fois : sans cette mémoire, la vérification quotidienne reposterait la
même notification chaque jour, ce qui apprend à l'ignorer.

Un fork ne change que `UPDATE_REPO` : rien d'autre dans le code ne nomme le
dépôt. `UPDATE_API` pointe la racine de l'API, pour qu'une instance GitHub
Enterprise reste atteignable sans toucher au code.

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

### Ports et accès aux services

Chaque conteneur affiche ses ports publiés, et chaque stack affiche ceux de
tous ses conteneurs. Une pastille mène au service. Seul le port de l'hôte est
écrit : c'est lui qui suffit à s'y rendre.

Rien de tout cela ne coûte un appel de plus. `Ports` et `HostConfig` arrivent
déjà dans la réponse que l'application télécharge pour lister les conteneurs ;
elle les jetait.

Quatre décisions viennent de la mesure sur une instance réelle :

- **Déduplication.** 46 des 96 entrées `Ports` étaient la même liaison rapportée
  deux fois, en IPv4 puis en IPv6. Sans dédoublonnage, chaque port s'afficherait
  en double.
- **Pas de lien en UDP ni sur la boucle locale.** Un port UDP ne porte pas de
  HTTP ; un port lié à `127.0.0.1` est joignable par l'hôte et par lui seul. Les
  deux gardent leur pastille et perdent leur lien, plutôt que d'en offrir un mort.
- **Le mode réseau explique les conteneurs muets.** Sur 26 conteneurs sans port,
  7 sont en réseau `host` — Docker ne rapporte alors rien, pas même un port privé —
  et 8 partagent la pile d'un autre conteneur. L'application va chercher leurs
  ports par un `inspect`, décrit ci-dessous, et se rabat sur la mention du mode
  réseau quand il ne donne rien.
- **Plafond d'affichage.** Un conteneur mesuré publie 24 ports, la moyenne est
  de 1. Au-delà de quatre pastilles, le reste se déplie à la demande.

#### Les conteneurs que la liste ne décrit pas

`/containers/json` ne rapporte que des **liaisons de ports**, et un conteneur en
réseau `host` ou en réseau partagé n'en a aucune. Son port existe pourtant. Il
faut alors un `inspect` par conteneur : l'application ne le fait que pour ceux
dont la liste est vide et dont le mode réseau explique l'absence — 15 appels au
lieu de 48 sur l'instance mesurée, lancés six à la fois. Un `inspect` qui échoue
n'est pas une erreur : le conteneur retombe sur sa mention de mode réseau.

- **Réseau `host`** : le port d'écoute du service est celui de la machine, sans
  traduction. C'est le `EXPOSE` de l'image qui le donne — souvent juste, jamais
  garanti. Le port s'affiche comme les autres ; le descriptif d'accessibilité
  précise qu'il est déduit.
- **Réseau partagé** : la cible publie parfois vingt ports, et rien dans la liste
  ne dit lequel appartient au conteneur qui la rejoint. Croiser ce qu'il expose
  avec ce qu'elle publie le dit exactement, sans rien deviner.

#### Choisir le port à la main

Aucune règle ne peut désigner le port qui porte l'interface web. Un client
BitTorrent en publie un pour ses pairs et un pour son interface, et rien dans
l'API ne les distingue ; un service en réseau `host` dont l'image déclare
`EXPOSE 8080` peut très bien écouter sur 8089 parce qu'une variable
d'environnement l'a décidé.

Le menu d'un conteneur ouvre donc **Port du raccourci…** : les ports détectés
sont proposés d'un geste, et la saisie libre couvre le cas où le bon port
n'apparaît nulle part. Le port choisi passe en tête, ne se replie jamais
derrière un « +8 », et se distingue des autres à la couleur. Un champ vide
revient au comportement automatique.

Le réglage est gardé sous le **nom** du conteneur, pas sous son identifiant :
un `compose up` recrée le conteneur avec un nouvel identifiant et garde son nom,
et le réglage doit survivre précisément à ce moment-là. Il part dans la
sauvegarde chiffrée avec le reste.

L'hôte visé par le lien n'est pas deviné, parce que **Portainer et Docker ne
tournent pas forcément sur la même machine** : un port publié appartient à
celle qui héberge le démon. Trois sources répondent, dans cet ordre — le
`PublicURL` de l'environnement quand l'administrateur l'a rempli, l'adresse du
démon quand elle est en `tcp://`, et à défaut l'hôte du Portainer configuré.
Une liaison sur une adresse précise plutôt que sur toutes les interfaces prime
sur les trois : elle seule répond.

Le protocole, lui, est deviné : `https` sur 443, 8443 et 9443, `http` ailleurs.
La liste est courte à dessein — deviner `https` à tort donne une erreur de
certificat illisible, deviner `http` à tort donne une redirection que le
navigateur suit tout seul.

### Noms personnalisés

Le menu d'un stack ou d'un conteneur ouvre **Renommer…** : un nom à soi et une
courte description. Le nom officiel n'est jamais remplacé — il reste affiché
sous le nom choisi, parce que c'est lui qu'on tape dans un compose et qu'on lit
dans un log. La description n'apparaît que dans les vues détaillées ; les
tuiles de raccourci n'en ont pas la place.

**La clé porte le type**, et ce n'est pas une précaution théorique : sur
l'instance mesurée, **17 des 31 stacks portent aussi le nom d'un conteneur**
(`komga`, `calibre-web`, `komf`…). Sans le type dans la clé, renommer le stack
`komga` renommerait le conteneur `komga`.

Elle porte aussi le **nom** et non l'identifiant. Pour un conteneur, c'est un
`compose up` qui change l'identifiant ; pour un stack, c'est pire : il bascule
de `managed:12` à `derived:3:komga` dès que `/api/stacks` cesse de le voir, ce
qui concerne neuf stacks sur trente et un.

Trois conséquences qu'il aurait été facile de manquer :

- **La recherche porte sur les deux noms et sur la description.** Un nom
  personnalisé introuvable à la recherche serait un nom perdu.
- **Le tri suit le nom affiché.** Trier sur le nom officiel donnerait une liste
  qui paraît désordonnée.
- **Le widget et la tuile affichent le nom choisi.** Ils ne peuvent rien
  résoudre au moment de se dessiner — quelques millisecondes de vie — donc c'est
  l'instantané qui porte le nom affiché, et renommer un stack le régénère.

### Favoris

Un troisième onglet, après Stacks et Conteneurs. Le menu d'un conteneur
l'y ajoute ; l'onglet se lit de deux façons, au choix :

- **Raccourcis** : une tuile par conteneur, réduite à son nom et à son port.
  Un appui ouvre le service. C'est un lanceur, pas un tableau de bord.
- **Détaillé** : la carte complète de l'onglet Conteneurs — état, image, ports,
  actions, logs.

Le port de la tuile est celui choisi à la main s'il existe, sinon le premier
port TCP joignable. Quand il n'y en a aucun, la tuile n'est pas morte pour
autant : l'appui ouvre le choix du port, qui est précisément ce qui lui manque.

Ces favoris-là **ne sont pas ceux du widget**. L'étoile d'un stack veut dire
« épingler au widget » et alimente `FavoriteStack`, que le widget, la tuile
Quick Settings et `StackActionWorker` retrouvent par leur `stackKey` ; un
conteneur rangé au même endroit n'aurait aucun stack correspondant et casserait
le widget sans bruit. Deux étoiles pour deux sens sur le même écran étant par
ailleurs un piège, le favori de conteneur vit dans le menu, avec un marque-page
pour icône.

Un favori dont le conteneur a disparu — renommé, supprimé, environnement hors
ligne — reste affiché et grisé. Le masquer le rendrait impossible à retirer, au
moment exact où on veut le faire.

Comme les ports épinglés, favoris et mode d'affichage sont gardés sous le **nom**
du conteneur et partent dans la sauvegarde chiffrée.

### Logs

Chaque conteneur a un bouton de logs, dans l'onglet Conteneurs comme sous un
stack déplié. L'écran propose 100, 500 ou 2000 lignes, l'horodatage à la
demande, un filtre de lignes, la copie du texte affiché, et un suivi en direct.

Le suivi procède par **sondage toutes les trois secondes**, pas par flux : la
route Docker sait diffuser en continu, mais une connexion maintenue ouverte à
travers un VPN mobile se coupe sans prévenir. Moins élégant, nettement plus
prévisible.

Les lignes défilent horizontalement par défaut : un retour à la ligne forcé
rendrait impossible de voir où commence l'entrée suivante. Mais une stack trace
ou un JSON d'une seule ligne est illisible ainsi, d'où la bascule **Retour à la
ligne**, qui replie les lignes et s'appuie sur l'alternance de teinte pour
rendre la frontière entre entrées.

### Sauvegarde

Une désinstallation efface le DataStore **et** la clé du Keystore qui scelle les
jetons. Sans sauvegarde, chaque réinstallation impose de tout ressaisir.

L'écran **Sauvegarde** exporte les serveurs et les stacks épinglés dans un
fichier chiffré en **AES-256-GCM**, par une clé dérivée en **PBKDF2-HMAC-SHA256,
210 000 itérations** (recommandation OWASP), sel et IV tirés au hasard à chaque
export. Les paramètres de dérivation voyagent dans le fichier : une sauvegarde
écrite aujourd'hui reste lisible par une version future qui aurait durci ses
réglages.

Le chiffrement repose sur une **phrase de passe**, pas sur le Keystore, et c'est
tout l'intérêt : une sauvegarde scellée par une clé qui disparaît à la
désinstallation serait illisible au moment précis où on en a besoin.

GCM authentifie le message : une mauvaise phrase de passe ne produit pas un
déchiffrement silencieusement faux, elle est rejetée. Rien ne permet de
retrouver une phrase de passe perdue.

L'écriture et la lecture passent par le Storage Access Framework : l'utilisateur
choisit l'emplacement, et l'application ne demande aucune permission de
stockage. L'import restaure par-dessus la configuration en place en conservant
les identifiants, donc réimporter deux fois la même sauvegarde ne crée pas de
doublons.

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
`excludeSnapshots`.

`probe/probe-zima.ps1` sonde une instance ZimaOS ou CasaOS. Sa première phase
ne demande aucun identifiant : l'interface de ZimaOS est une application Vue
dont les clients d'API sont générés, si bien que **chaque route figure en clair
dans les fichiers JavaScript**. Les extraire évite d'avoir à en deviner une
seule. La seconde phase, optionnelle, se connecte et interroge en lecture seule
les routes sans paramètre ; le mot de passe est saisi au clavier et le jeton
obtenu n'est jamais écrit sur le disque. `probe/probe-actions.ps1` valide les routes d'action et le
format des logs ; il arrête puis redémarre un stack, et refuse donc de
s'exécuter ailleurs que sur `localhost` sans `-Force` explicite.

## Licence

MIT.
