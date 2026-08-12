package com.example.listazakupow.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith

@Composable
fun UserHeader(
    imie: String,
    liczbaProduktow: Int,
    onLogout: () -> Unit
) {

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 8.dp
        )
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {

            Column {

                Text(
                    text = "👋 Witaj, $imie",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(
                    modifier = Modifier.height(4.dp)
                )
                AnimatedContent(
                    targetState = liczbaProduktow,
                    transitionSpec = {
                        slideInVertically { it } togetherWith
                                slideOutVertically { -it }
                    },
                    label = "licznik"
                ) { liczba ->

                    Text(
                        text = "🛒 $liczba produktów",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

            }

            Button(
                onClick = onLogout,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Wyloguj")
            }
        }
    }
}