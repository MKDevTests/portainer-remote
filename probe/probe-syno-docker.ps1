<#
  Trouve les parametres que SYNO.Docker.Container / list exige.

  Le sondage precedent a recu « erreur 114 » sur cette route. 114 ne veut pas
  dire « interdit » ni « absent » : il veut dire « il manque des parametres ».
  L'appel avait ete fait avec api, version, method et rien d'autre. DSM ne
  documente pas ce qu'il attend en plus, et le deviner depuis l'application
  n'est pas une methode : ce script essaie une liste finie de combinaisons et
  rapporte celle qui repond.

  Ce que ce script n'ecrit jamais sur le disque :

    - aucun nom de conteneur, aucun nom d'image, aucun chemin, aucun volume :
      ils diraient ce qui tourne sur la machine, et ces fichiers finissent dans
      un depot public ;
    - aucune definition de conteneur : SYNO.Docker.Container / get renvoie les
      variables d'environnement, donc des mots de passe. Cette route n'est
      appelee dans aucune variante ;
    - aucun identifiant de session, aucune adresse.

  Ce qu'il ecrit : la forme de la reponse (noms des champs et leur type) et,
  pour une poignee de champs d'etat, la liste de leurs valeurs distinctes -
  « running », « exited ». Sans elles, l'application ne saurait pas quoi
  afficher.

  Aucune ecriture cote NAS : seules des methodes de lecture sont appelees.
  Rien n'est demarre, arrete ni modifie.

  Usage :
    .\probe-syno-docker.ps1 -BaseUrl http://100.93.6.123:5000
    .\probe-syno-docker.ps1 -BaseUrl http://100.93.6.123:5000 -Brut

  -Brut affiche le corps des refus, tronque, identifiant de session et adresses
  retires. Une reponse ACCEPTEE n'est jamais affichee : elle nommerait les
  conteneurs.
#>
param(
  [Parameter(Mandatory = $true)][string]$BaseUrl,
  [string]$Tag = "syno",
  [switch]$SkipTls,
  [switch]$Brut
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")
if ($BaseUrl -notmatch '^https?://') { $BaseUrl = "http://$BaseUrl" }
$out = Join-Path $PSScriptRoot $Tag
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Path $out | Out-Null }

if ($SkipTls) {
  Add-Type @"
using System.Net; using System.Security.Cryptography.X509Certificates;
public class TrustAllSynoDocker : ICertificatePolicy {
  public bool CheckValidationResult(ServicePoint s, X509Certificate c, WebRequest r, int p) { return true; }
}
"@
  [System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAllSynoDocker
  [System.Net.ServicePointManager]::SecurityProtocol  = [System.Net.SecurityProtocolType]::Tls12
}

# Les seuls champs dont les VALEURS seront ecrites. Ce sont des etats, pas des
# noms : ils ne disent rien de ce qui tourne, et sans eux l'application ne peut
# pas traduire « exited » en « arrete ».
$etats = @(
  'status', 'Status', 'state', 'State', 'up_status', 'health', 'Health',
  'is_package', 'is_ddsm', 'Running', 'Paused', 'Restarting', 'Dead', 'OOMKilled'
)

# Les endroits ou une cle N'EST PAS un nom de champ.
#
# Sous « Networks », chaque cle est un nom de reseau ; sous « Labels », un nom
# d'etiquette. Les deux sont choisis par qui a ecrit le compose, et disent donc
# ce qui tourne sur la machine. Un premier releve les avait ecrits : ces
# sous-arbres sont maintenant comptes, jamais nommes.
$cachees = @('Networks', 'Labels')

# Windows PowerShell refuse un objet JSON dont deux cles ne different que par
# la casse - « State » et « state », que Docker met cote a cote. Ce lecteur-ci
# les accepte : il rend des dictionnaires ordinaux.
Add-Type -AssemblyName System.Web.Extensions
$lecteurJson = New-Object System.Web.Script.Serialization.JavaScriptSerializer
$lecteurJson.MaxJsonLength = [int]::MaxValue

function Read-Error($response) {
  if (A-Cle $response 'error') {
    $e = $response['error']
    if (A-Cle $e 'code') { return [int]$e['code'] }
  }
  if ($response -and $response.error -and $response.error.code) { return [int]$response.error.code }
  return 0
}

# Un refus n'est pas toujours un JSON de la forme attendue. Quand DSM rend
# autre chose - une page, un corps vide, un objet d'erreur autrement construit -
# l'objet parse n'a ni « success » ni « error.code », et le code lu vaut 0.
# C'est exactement ce qui est arrive : il faut alors regarder le texte, pas
# l'objet. Cette fonction rend donc les deux.
function Lire-Reponse($uri, $methode, $corps) {
  $brut = $null
  try {
    if ($methode -eq 'POST') {
      $w = Invoke-WebRequest -Uri $uri -Method Post -Body $corps -TimeoutSec 20 -UseBasicParsing
    } else {
      $w = Invoke-WebRequest -Uri $uri -Method Get -TimeoutSec 20 -UseBasicParsing
    }
    $brut = $w.Content
  } catch {
    # Un refus HTTP porte souvent son explication dans le corps : on le lit
    # au lieu de ne garder que le message de l'exception.
    $reponse = $_.Exception.Response
    if ($reponse) {
      try {
        $lecteur = New-Object System.IO.StreamReader($reponse.GetResponseStream())
        $brut = $lecteur.ReadToEnd()
      } catch { }
    }
    if (-not $brut) { $brut = "(aucun corps) " + $_.Exception.Message }
  }
  $objet = $null
  try { $objet = $lecteurJson.DeserializeObject($brut) } catch { }
  return [pscustomobject]@{ objet = $objet; brut = $brut }
}

# Le lecteur rend des Dictionary<string, object>. Leur methode « Contains »
# n'appartient qu'a l'interface non generique : sans le transtypage ci-dessous,
# l'appel echoue, et l'echec est silencieux au milieu d'une boucle.
function A-Cle($dico, $cle) {
  if ($dico -is [System.Collections.IDictionary]) {
    return ([System.Collections.IDictionary]$dico).Contains($cle)
  }
  return $false
}

# Un acces de dictionnaire qui ne se plaint pas d'une cle absente.
function Valeur($dico, $cle) {
  if (A-Cle $dico $cle) { return $dico[$cle] }
  return $null
}

<#
  Ce qui part a l'ecran sous -Brut.

  La premiere version affichait le corps tronque a 400 caracteres. Elle a
  affiche une reponse ACCEPTEE - donc des noms d'images et un hash de compose -
  parce que le script avait classe cette reponse comme un refus : la garde
  « seulement sur un refus » ne protege rien si le classement se trompe.

  Cette version ne fait plus reposer la protection sur le classement. Elle
  n'affiche jamais de valeurs : seulement les noms des cles de premier niveau,
  et le contenu de « error », qui ne porte que des codes.
#>
function Montrer-Brut($lu) {
  if (-not $Brut) { return }
  if ($lu.objet -is [System.Collections.IDictionary]) {
    $cles = (@($lu.objet.Keys)) -join ", "
    $detail = ""
    $erreur = Valeur $lu.objet 'error'
    if ($erreur -is [System.Collections.IDictionary]) {
      $detail = " · error : code " + (Valeur $erreur 'code')
    }
    Write-Host ("          cles : {0}{1}" -f $cles, $detail) -ForegroundColor DarkYellow
    return
  }
  Write-Host ("          reponse non lisible, {0} octets" -f ([string]$lu.brut).Length) -ForegroundColor DarkYellow
}

<#
  Une reponse acceptee ne se reconnait pas a « success ».

  C'est l'erreur qui a fait declarer sept refus alors que DSM repondait : le
  script attendait « success: true », et le corps commencait par « data ». Deux
  raisons de ne pas s'y fier :

    - une charge presente vaut acceptation, meme si le champ « success » est
      ailleurs dans l'objet ou absent ;
    - quand PowerShell n'a pas su lire le JSON (cles differant par la casse),
      il n'y a aucun objet a interroger : il reste le texte.
#>
function Est-Acceptee($lu) {
  if ($lu.objet -is [System.Collections.IDictionary]) {
    if ((A-Cle $lu.objet 'success') -and $lu.objet['success']) { return $true }
    return (A-Cle $lu.objet 'data')
  }
  return $false
}

function Nommer($code) {
  switch ($code) {
    102 { "API inconnue" }
    103 { "methode inconnue" }
    104 { "version non supportee" }
    105 { "permission refusee pour ce compte" }
    114 { "parametres manquants" }
    119 { "session invalide" }
    default { "code $code" }
  }
}

<#
  Rend l'arborescence des champs et le type de chacun, jamais leur contenu.

  Une precaution s'ajoute ici, apprise a nos depens : sous « Networks » et sous
  « Labels », les cles ne sont pas des noms de champs mais des noms choisis par
  qui a ecrit le compose. Un premier releve avait donc ecrit les noms des
  reseaux, c'est-a-dire les noms des piles. Ces sous-arbres sont maintenant
  comptes et decrits, jamais nommes.
#>
function Get-Shape($node, $prefix, $acc, $parent) {
  if ($null -eq $node) { [void]$acc.Add("$prefix : null"); return }

  if ($node -is [System.Collections.IDictionary]) {
    if ($cachees -contains $parent) {
      [void]$acc.Add("$prefix{} : $($node.Count) entrees, noms non ecrits")
      if ($node.Count -gt 0) {
        $premiere = @($node.Keys)[0]
        Get-Shape $node[$premiere] "$prefix{}" $acc ''
      }
      return
    }
    foreach ($cle in $node.Keys) {
      $chemin = $cle
      if ($prefix) { $chemin = "$prefix.$cle" }
      Get-Shape $node[$cle] $chemin $acc $cle
    }
    return
  }

  if ($node -is [System.Collections.IEnumerable] -and $node -isnot [string]) {
    $items = @($node)
    [void]$acc.Add("$prefix[] : $($items.Count) entrees")
    if ($items.Count -gt 0) { Get-Shape $items[0] "$prefix[]" $acc $parent }
    return
  }

  $type = switch ($node.GetType().Name) {
    "String"  { "texte ($($node.Length) car.)" }
    "Boolean" { "booleen" }
    default   { "nombre" }
  }
  [void]$acc.Add("$prefix : $type")
}

# ------------------------------------------------------------------ catalogue

$catalogue = $null
try {
  $catalogue = Invoke-RestMethod -TimeoutSec 20 -Method Get `
    -Uri "$BaseUrl/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=all"
} catch {
  Write-Host ("Injoignable : {0}" -f $_.Exception.Message) -ForegroundColor Red
  return
}
if (-not $catalogue.success) { Write-Host "Catalogue refuse." -ForegroundColor Red; return }

function Find-Api($nom) {
  $p = $catalogue.data.PSObject.Properties | Where-Object { $_.Name -eq $nom } | Select-Object -First 1
  if (-not $p) { return $null }
  return [pscustomobject]@{
    api = $nom; path = $p.Value.path; minVer = $p.Value.minVersion; maxVer = $p.Value.maxVersion
  }
}

$authApi = Find-Api 'SYNO.API.Auth'
$docker  = Find-Api 'SYNO.Docker.Container'
if (-not $docker)  { Write-Host "SYNO.Docker.Container absent de ce DSM." -ForegroundColor Red; return }
if (-not $authApi) { Write-Host "SYNO.API.Auth absent." -ForegroundColor Red; return }

Write-Host ""
Write-Host ("SYNO.Docker.Container : {0} v{1}-{2}" -f $docker.path, $docker.minVer, $docker.maxVer) -ForegroundColor Cyan
Write-Host ""

$user = Read-Host "  Utilisateur DSM"
$sec  = Read-Host "  Mot de passe" -AsSecureString

$sid = $null
try {
  $pass = [System.Net.NetworkCredential]::new("", $sec).Password
  $form = @{
    api = 'SYNO.API.Auth'; version = [string]$authApi.maxVer; method = 'login'
    account = $user; passwd = $pass; session = 'PortainerRemoteProbe'; format = 'sid'
  }
  $login = Invoke-RestMethod -TimeoutSec 20 -Method Post -Uri "$BaseUrl/webapi/$($authApi.path)" -Body $form
  if (-not $login.success -and ((Read-Error $login) -eq 403 -or (Read-Error $login) -eq 406)) {
    $form.otp_code = Read-Host "  Code de verification en deux etapes"
    $login = Invoke-RestMethod -TimeoutSec 20 -Method Post -Uri "$BaseUrl/webapi/$($authApi.path)" -Body $form
    $form.otp_code = $null
  }
  $form.passwd = $null
  $pass = $null
  if (-not $login.success) {
    Write-Host ("  Connexion refusee : {0}." -f (Nommer (Read-Error $login))) -ForegroundColor Red
    return
  }
  $sid = $login.data.sid
  Write-Host "  Session ouverte." -ForegroundColor Green
  Write-Host ""

  # --------------------------------------------------------- les combinaisons
  #
  # De la plus pauvre a la plus riche. DSM attend ses valeurs en JSON : une
  # chaine porte ses guillemets, un tableau ses crochets. C'est la cause la
  # plus frequente d'un 114 qui persiste alors que le nom du parametre est bon.
  $essais = @(
    @{ nom = 'aucun parametre';            p = @{} },
    @{ nom = 'limit + offset';             p = @{ limit = '-1'; offset = '0' } },
    @{ nom = 'limit + offset + type';      p = @{ limit = '-1'; offset = '0'; type = '"all"' } },
    @{ nom = 'limit borne + type';         p = @{ limit = '100'; offset = '0'; type = '"all"' } },
    @{ nom = 'type + tri';                 p = @{ limit = '-1'; offset = '0'; type = '"all"'; sort_by = '"name"'; sort_dir = '"ASC"' } },
    @{ nom = 'type + additional';          p = @{ limit = '-1'; offset = '0'; type = '"all"'; additional = '["is_ddsm","is_package"]' } },
    @{ nom = 'type + additional + projet'; p = @{ limit = '-1'; offset = '0'; type = '"all"'; additional = '["is_ddsm","is_package","project"]' } }
  )

  $versions = @($docker.maxVer)
  if ($docker.minVer -ne $docker.maxVer) { $versions += $docker.minVer }

  $gagnant = $null
  $reponse = $null
  $rapport = New-Object System.Collections.ArrayList

  foreach ($v in $versions) {
    foreach ($e in $essais) {
      if ($gagnant) { break }
      $qs = ($e.p.GetEnumerator() | ForEach-Object {
        "{0}={1}" -f $_.Key, [uri]::EscapeDataString($_.Value)
      }) -join "&"
      $uri = "{0}/webapi/{1}?api=SYNO.Docker.Container&version={2}&method=list&_sid={3}" -f `
             $BaseUrl, $docker.path, $v, [uri]::EscapeDataString($sid)
      if ($qs) { $uri = "$uri&$qs" }

      try {
        $lu = Lire-Reponse $uri 'GET' $null
        if (Est-Acceptee $lu) {
          Write-Host ("  v{0}  OK       {1}" -f $v, $e.nom) -ForegroundColor Green
          [void]$rapport.Add(("v{0}  OK       GET   {1}   [{2}]" -f $v, $e.nom, $qs))
          $gagnant = [pscustomobject]@{ version = $v; nom = $e.nom; qs = $qs; verbe = 'GET' }
          $reponse = $lu
        } else {
          $code = Read-Error $lu.objet
          Write-Host ("  v{0}  {1,-7} {2}" -f $v, $code, $e.nom) -ForegroundColor DarkGray
          [void]$rapport.Add(("v{0}  {1,-7} GET   {2}   ({3})" -f $v, $code, $e.nom, (Nommer $code)))
          Montrer-Brut $lu
        }
      } catch {
        # Un echec ici est un defaut du script, pas un refus de DSM : il se
        # voit, sinon la boucle rend « rien du tout » sans dire pourquoi.
        Write-Host ("  v{0}  echec   {1} : {2}" -f $v, $e.nom, $_.Exception.Message) -ForegroundColor Red
        [void]$rapport.Add(("v{0}  echec   GET   {1}   {2}" -f $v, $e.nom, $_.Exception.Message))
      }
    }
  }

  # Dernier recours : la meme requete en POST. Certaines routes DSM ne lisent
  # leurs parametres que dans le corps.
  if (-not $gagnant) {
    Write-Host ""
    Write-Host "  Aucune combinaison en GET. Meme requete en POST :" -ForegroundColor Cyan
    foreach ($e in $essais) {
      if ($gagnant) { break }
      $body = @{
        api = 'SYNO.Docker.Container'; version = [string]$docker.maxVer
        method = 'list'; _sid = $sid
      }
      foreach ($k in $e.p.Keys) { $body[$k] = $e.p[$k] }
      try {
        $lu = Lire-Reponse "$BaseUrl/webapi/$($docker.path)" 'POST' $body
        if (Est-Acceptee $lu) {
          Write-Host ("  POST  OK      {0}" -f $e.nom) -ForegroundColor Green
          [void]$rapport.Add(("POST  OK      {0}" -f $e.nom))
          $gagnant = [pscustomobject]@{ version = $docker.maxVer; nom = $e.nom; qs = ''; verbe = 'POST' }
          $reponse = $lu
        } else {
          $code = Read-Error $lu.objet
          Write-Host ("  POST  {0,-7} {1}" -f $code, $e.nom) -ForegroundColor DarkGray
          [void]$rapport.Add(("POST  {0,-7} {1}   ({2})" -f $code, $e.nom, (Nommer $code)))
          Montrer-Brut $lu
        }
      } catch {
        Write-Host ("  POST  echec   {0} : {1}" -f $e.nom, $_.Exception.Message) -ForegroundColor Red
        [void]$rapport.Add(("POST  echec   {0}   {1}" -f $e.nom, $_.Exception.Message))
      }
    }
  }

  # ----------------------------------------------------------- ce qu'on garde
  Write-Host ""
  if (-not $gagnant) {
    Write-Host "  Aucune combinaison acceptee." -ForegroundColor Yellow
    Write-Host "  Si toutes rendent 105, le compte n'a pas le droit d'ouvrir Container" -ForegroundColor DarkGray
    Write-Host "  Manager : c'est un reglage DSM, pas un probleme de parametres." -ForegroundColor DarkGray
  } else {
    Write-Host ("  Acceptee : {0}, en v{1}, {2}" -f $gagnant.nom, $gagnant.version, $gagnant.verbe) -ForegroundColor Green
    if ($gagnant.qs) { Write-Host ("  {0}" -f $gagnant.qs) -ForegroundColor DarkGray }

    $donnees = Valeur $reponse.objet 'data'
    $acc = New-Object System.Collections.ArrayList
    Get-Shape $donnees "" $acc ''
    $acc | Out-File (Join-Path $out "docker_container_list_forme.txt") -Encoding utf8
    Write-Host ("  Forme ecrite : {0} lignes, aucune valeur." -f $acc.Count) -ForegroundColor DarkGray

    # Les valeurs d'etat, et elles seules.
    $liste = @()
    if ($donnees -is [System.Collections.IDictionary]) {
      foreach ($cle in $donnees.Keys) {
        $v = $donnees[$cle]
        if ($v -is [System.Collections.IEnumerable] -and $v -isnot [string] -and
            $v -isnot [System.Collections.IDictionary]) {
          $liste = @($v)
        }
      }
    }
    $vus = @{}
    foreach ($item in $liste) {
      if ($item -isnot [System.Collections.IDictionary]) { continue }
      foreach ($champ in $etats) {
        if (-not (A-Cle $item $champ)) { continue }
        $v = $item[$champ]
        if ($v -is [System.Collections.IDictionary] -or
            ($v -is [System.Collections.IEnumerable] -and $v -isnot [string])) { continue }
        if (-not $vus.ContainsKey($champ)) { $vus[$champ] = New-Object System.Collections.ArrayList }
        $val = [string]$v
        if ($vus[$champ] -notcontains $val) { [void]$vus[$champ].Add($val) }
      }
    }
    $lignes = New-Object System.Collections.ArrayList
    [void]$lignes.Add(("{0} conteneurs, noms non ecrits" -f @($liste).Count))
    foreach ($k in $vus.Keys) { [void]$lignes.Add(("{0} : {1}" -f $k, ($vus[$k] -join ", "))) }
    $lignes | Out-File (Join-Path $out "docker_container_etats.txt") -Encoding utf8
    $lignes | ForEach-Object { Write-Host ("  {0}" -f $_) -ForegroundColor DarkGray }
  }

  $rapport | Out-File (Join-Path $out "docker_114.txt") -Encoding utf8
  Write-Host ""
  Write-Host ("Ecrit dans {0}" -f $out) -ForegroundColor Green

} finally {
  if ($sid) {
    try {
      $adieu = "{0}/webapi/{1}?api=SYNO.API.Auth&version=1&method=logout&session=PortainerRemoteProbe&_sid={2}" -f `
               $BaseUrl, $authApi.path, [uri]::EscapeDataString($sid)
      Invoke-RestMethod -TimeoutSec 10 -Method Get -Uri $adieu | Out-Null
    } catch { }
    $sid = $null
  }
}
