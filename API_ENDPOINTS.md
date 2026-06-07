# 🔒 Endpoints API Unifiés et Sécurisés

## Base URL
```
/api
```

## Authentication
- **Type:** JWT Bearer Token
- **Header:** `Authorization: Bearer {token}`
- **CSRF Token:** `X-CSRF-TOKEN: {token}` (pour mutations en production)

## Leave Balance (Problème 3)
```
GET  /api/leave-balance/{userId}/{leaveType}
  ✅ Backend = source de vérité unique
  ✅ RH peut voir soldes d'employés de leur pays
  ✅ Employés voient seulement leurs soldes
```

## Demandes de Congés (Unifié)
```
GET    /api/demandes              → Mes demandes
GET    /api/demandes/{id}         → Détail demande
POST   /api/demandes              → Créer demande
       ✅ Validation inactivité (Problème 11)
       ✅ Vérification chevauchement (Problème 9)
```

## Décisions RH
```
GET    /api/rh/requests           → Historique
GET    /api/rh/requests/pending   → En attente
POST   /api/rh/requests/{id}/decision
       ✅ Cross-country access control (Problème 2)
       ✅ Country isolation (RH ne voit que son pays)
```

## Analyse IA (Problème 1)
```
GET    /api/ai/suggest-dates      → Dates optimales
GET    /api/ai/detect-conflicts   → Détection conflits
GET    /api/ai/impact-score       → Impact analyse (déterministe, pas random!)
       ✅ Utilise données réelles au lieu de Math.random()
```

## Statistiques (Problème 4, Phase 2)
```
GET    /api/stats/country?country={code}  → Stats par pays (RH)
GET    /api/stats/global                   → Stats globales (ADMIN seulement)
       ✅ Stats isolées par pays
       ✅ RH ne voit que son pays
```

## Sécurité Appliquée
- 🔒 **Authentication:** Utilisateur du JWT, pas paramètre (évite escalade)
- 🔒 **CSRF:** Protégé en production (`/api/auth/login` excepté)
- 🔒 **CORS:** Headers restreints (pas de "*")
- 🔒 **Access Control:** Country-based + Role-based
- 🔒 **N+1 Prevention:** Toutes queries avec JOIN FETCH
- 🔒 **Null-Safety:** Vérifications centralisées
- 🔒 **Race Conditions:** Reload avant transition de statut
- 🔒 **Inactivité:** Utilisateurs inactifs bloqués
- 🔒 **Audit:** Tous les changements loggés

## DTOs Utilisés
- ✅ `LeaveBalanceDto` - Soldes de congés (source backend)
- ✅ `HrLeaveRequestResponse` - Demandes pour RH
- ✅ `DateSuggestionDto` - Suggestions IA
- ✅ `ConflictDetectionDto` - Détection conflits

## Erreurs Communes (Gérées)
```
401 - Non authentifié
403 - Accès refusé (cross-country, inactivité, etc)
404 - Ressource non trouvée
400 - État invalide
500 - Erreur serveur (loggée)
```
