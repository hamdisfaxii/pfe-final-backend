# Mise à jour des règles d'accrual et des quotas de congés — 2026

## Résumé exécutif

Cette mise à jour aligne les quotas de congés payés et la gestion des RTT avec les règles métier précises de chaque pays.

---

## Changements de valeurs de quotas

### Pourquoi les valeurs de quotas ont changé

Les quotas par défaut ont été corrigés pour correspondre aux règles métier réelles **calculées mensuellement** plutôt que des montants arbitraires :

#### **Tunisie (TN)**
- **Avant** : 30 jours/an
- **Après** : **22 jours/an**
- **Raison** : Règle métier = 1,83 jour/mois
  - Calcul : 1,83 × 12 = 21,96 ≈ **22 jours/an**
  - Cette valeur correspond à la règle légale tunisienne

#### **Maroc (MA)**
- **Avant** : 22 jours/an
- **Après** : **18 jours/an**
- **Raison** : Règle métier = 1,5 jour/mois
  - Calcul : 1,5 × 12 = **18 jours/an**
  - Cette valeur correspond à la règle légale marocaine

#### **France (FR)**
- **Congés payés : Avant** : 25 jours/an → **Après** : 25 jours/an (inchangé)
  - Raison : Règle métier = 2,08 jour/mois
  - Calcul : 2,08 × 12 ≈ 24,96 ≈ 25 jours/an ✓
- **RTT (nouveau) : Après** : **9 jours/an**
  - Raison : Configuration par année
  - 2026 : 0,75 jour/mois × 12 = 9 jours/an
  - La valeur RTT est maintenant **séparée** des congés payés (avant elle était fusionnée avec "Sortie courte")

---

## Changements de configuration

### Configuration RTT par année

Une nouvelle configuration a été ajoutée pour gérer la menualisation de l'RTT par année :

```javascript
const RTT_BY_YEAR = {
  2026: 0.75, // 0.75 jour/mois → 9 jours/an
};

function rttDaysForYear(year) {
  const ratePerMonth = Number(RTT_BY_YEAR[year] ?? 0);
  return Number((ratePerMonth * 12));
}
```

Cela permet de configurer facilement le taux RTT pour chaque année future.

---

## Changements de gestion maladie

### Maladie uniquement en Tunisie

La gestion du congé maladie a été restreinte à la Tunisie uniquement :

- **Tunisie (TN)** : Maladie = 7 jours/an non-reportables
- **France (FR)** : Pas de ligne maladie
- **Maroc (MA)** : Pas de ligne maladie

**Raison** : Seule la Tunisie a un quota explicite de maladie. Les autres pays gèrent la maladie différemment.

**Changements backend** :
- `typesCongePays.TN.MALADIE` = 7 jours (conservé)
- `typesCongePays.FR.MALADIE` = **supprimé**
- `typesCongePays.MA.MALADIE` = **supprimé**

**Changements frontend** :
- Formulaire `NouvelleDemande.jsx` : Option "Congé maladie" visible uniquement pour les utilisateurs TN

---

## Reset annuel des soldes

Une nouvelle logique a été implémentée pour gérer le **non-report** des quotas annuels :

### Implémentation

Chaque solde enregistré inclut désormais un champ `year` :

```javascript
kongesSoldes[user.id][typeKey] = {
  utilise: 0,
  restant: initialJours,
  year: currentYear,  // NEW: Année associée au solde
};
```

### Logique de reset

Lors du calcul des soldes (endpoint `/api/conge/solde/:userId`) :
- Si le `year` du solde < année courante → **reset à zéro**
- Les jours non-utilisés l'année précédente ne sont pas reportés
- Les quotas annuels recommencent à `solde_initial` chaque année

---

## Impact sur les utilisateurs

### Affichage des cartes de congé

| Pays | Congés payés | RTT | Maladie | Sorties courtes | Parental | Enfant malade | Retards |
|------|--------------|-----|---------|-----------------|----------|---------------|---------|
| **TN** | 22 j | — | 7 j | — | 180 j | — | 12 j |
| **FR** | 25 j | 9 j | — | 10 j (compat.) | 180 j | 5 j | — |
| **MA** | 18 j | — | — | — | 120 j | — | 12 j |

---

## Tests et validation

### Endpoints modifiés

- `GET /api/conge/solde/:userId`
  - Retourne des quotas `solde_initial` alignés avec les règles métier
  - Recalcule RTT dynamiquement pour l'année en cours
  - Filtre les types non-applicables par pays

- `GET /api/hr/balances`
  - Affiche les balances avec quotas corrects
  - Reset automatique si année change

### Résultats des tests smoke

```
✅ GET /api/conge/solde/1 (TN user)
   - CONGES_PAYES: 22 (conforme 1.83 j/mois)
   - MALADIE: 7 (conforme règle TN)
   - PARENTAL: 180
   - ARRIVE_AUTORISATION: 12

✅ POST /api/auth/login (rh@company.com) 
   - Login OK après corrections du backend
```

---

## Fichiers modifiés

1. **Backend**
   - `mock-backend.js`
     - Mise à jour `typesCongePays[TN/FR/MA]` (lignes 355-388)
     - Suppression MALADIE de FR/MA
     - Ajout `RTT_BY_YEAR` configuration (lignes 396-402)
     - Ajout `rttDaysForYear()` helper (lignes 404-407)
     - Reset annuel dans `buildSoldeResponse()` (lignes 2032-2058)
     - Bugfix `dolibarrQualifiedTable()` (ligne 80)

2. **Frontend**
   - `src/pages/employee/NouvelleDemande.jsx`
     - Filtre option "Congé maladie" pour TN uniquement

---

## Notes de migration

- **Données existantes** : Les anciens soldes en cache (`mock-persist.json`) seront automatiquement reset si l'année a changé (logique non-report)
- **Backward compatibility** : Les endpoints continuent de retourner la même structure JSON
- **Configuration RTT** : Extensible facilement pour ajouter d'autres années (2027+)

---

## Prochaines étapes

1. ✅ Déployer sur environnement de test
2. ✅ Valider quotas avec exemple utilisateurs TN/MA/FR
3. ⏳ Vérifier les calculs jours ouvrables
4. ⏳ Tester non-report de maladie au changement d'année

---

**Date mise à jour** : 20 mai 2026  
**Version** : 1.0.0  
**Status** : Prêt pour déploiement
