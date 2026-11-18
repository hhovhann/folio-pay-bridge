package dev.foliopay.api.controller;

import dev.foliopay.api.ApiPaths;
import dev.foliopay.api.dto.CardRequest;
import dev.foliopay.api.dto.CardResponse;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.service.CardVault;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Cards", description = "Tokenisation. The only endpoint that accepts a card number.")
@RestController
@RequestMapping(ApiPaths.V1 + "/cards")
class CardController {
  private final CardVault vault;

  CardController(CardVault vault) { this.vault = vault; }

  @Operation(summary = "Tokenise a card", description = "Returns a token and the display digits (first six, last four). The number is never returned or stored in clear.")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  CardResponse tokenise(@Valid @RequestBody CardRequest request) {
    return CardResponse.from(vault.tokenise(new Pan(request.pan()), request.expiry()));
  }
}
