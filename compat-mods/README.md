# Compat Mods

Drop `.jar` files of mods that must be compatible with **PlayerSync** here for local analysis and testing.

## Purpose

- Reference bundles for writing compatibility shims (see `src/main/java/vip/fubuki/playersync/sync/addons/`).
- Local inspection of mod APIs, capabilities, and data structures.
- NOT loaded by the dev runtime — purely a staging folder for analysis.

## Rules

- `.jar` files are **git-ignored** — do not commit mod binaries.
- Keep one version per mod; rename with version suffix if multiple are needed (e.g. `sophisticatedbackpacks-1.21.1-3.23.0.jar`).

---

# Mods de compatibilité

Déposez les fichiers `.jar` des mods qui doivent être compatibles avec **PlayerSync** ici pour analyse et tests locaux.

## Objectif

- Bundles de référence pour écrire des shims de compatibilité (voir `src/main/java/vip/fubuki/playersync/sync/addons/`).
- Inspection locale des APIs, capabilities et structures de données des mods.
- Non chargé par le runtime de dev — dossier de staging uniquement pour analyse.

## Règles

- Les fichiers `.jar` sont **ignorés par git** — ne pas commit les binaires de mods.
- Une seule version par mod ; renommer avec le suffixe de version si plusieurs sont nécessaires (ex : `sophisticatedbackpacks-1.21.1-3.23.0.jar`).
