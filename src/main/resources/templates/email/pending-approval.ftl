<!DOCTYPE html>
<html lang="fr">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        body { font-family: Arial, sans-serif; background-color: #f5f5f5; }
        .container { max-width: 600px; margin: 20px auto; background-color: white; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .header { background-color: #f39c12; color: white; padding: 20px; text-align: center; border-radius: 8px 8px 0 0; }
        .content { padding: 20px; }
        .footer { background-color: #ecf0f1; padding: 15px; text-align: center; font-size: 12px; color: #7f8c8d; border-radius: 0 0 8px 8px; }
        .info-box { background-color: #fef5e7; padding: 15px; border-radius: 4px; margin: 15px 0; border-left: 4px solid #f39c12; }
        .info-box strong { color: #f39c12; }
        .btn-approve { display: inline-block; padding: 12px 28px; background-color: #27ae60; color: white; text-decoration: none; border-radius: 6px; margin: 8px 6px; font-weight: bold; font-size: 15px; }
        .btn-reject  { display: inline-block; padding: 12px 28px; background-color: #e74c3c; color: white; text-decoration: none; border-radius: 6px; margin: 8px 6px; font-weight: bold; font-size: 15px; }
        .btn-detail  { display: inline-block; padding: 8px 18px; background-color: #3498db; color: white; text-decoration: none; border-radius: 4px; margin: 6px 0; font-size: 13px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>⏳ Demande en Attente d'Approbation</h1>
        </div>
        
        <div class="content">
            <p>Bonjour <strong>${approverName}</strong>,</p>
            
            <p><strong>${requesterName}</strong> a soumis une nouvelle demande de congé qui nécessite votre approbation.</p>
            
            <div class="info-box">
                <p><strong>Détails de la demande :</strong></p>
                <ul>
                    <li><strong>Demandeur :</strong> ${requesterName}</li>
                    <li><strong>Type de congé :</strong> ${typeConge}</li>
                    <li><strong>Date de début :</strong> ${dateDebut}</li>
                    <li><strong>Date de fin :</strong> ${dateFin}</li>
                    <li><strong>Nombre de jours :</strong> ${nombreJours}</li>
                    <li><strong>Motif :</strong> ${motif}</li>
                </ul>
            </div>
            
            <p>Vous pouvez décider directement depuis cet email en cliquant sur l'un des boutons ci-dessous :</p>

            <p style="text-align: center; margin: 20px 0;">
                <a href="${backendUrl}/api/public/decision?token=${approveToken}" class="btn-approve">✅ Approuver</a>
                &nbsp;
                <a href="${backendUrl}/api/public/decision?token=${rejectToken}" class="btn-reject">❌ Rejeter</a>
            </p>

            <p style="text-align: center;">
                <a href="${appUrl}/rh/requests/${demandeId}" class="btn-detail">Voir dans le dashboard</a>
            </p>
            <p style="text-align: center; font-size: 12px; color: #7f8c8d; margin-top: 6px;">
                Lien dashboard : ${appUrl}/rh/requests/${demandeId}
            </p>
            
            <p>Cordialement,<br>
            <strong>L'équipe Gestion des Congés</strong></p>
        </div>
        
        <div class="footer">
            <p>Cet email a été généré automatiquement. Veuillez ne pas répondre directement.</p>
        </div>
    </div>
</body>
</html>
