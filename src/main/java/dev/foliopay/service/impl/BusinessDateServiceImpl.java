package dev.foliopay.service.impl;

import dev.foliopay.config.OperaProperties;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.gateway.OperaClient;
import dev.foliopay.service.BusinessDateService;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

@Service
class BusinessDateServiceImpl implements BusinessDateService {
  private final OperaClient opera;
  private final OperaProperties settings;

  BusinessDateServiceImpl(OperaClient opera, OperaProperties settings) {
    this.opera = opera;
    this.settings = settings;
  }

  @Override
  public LocalDate resolve(LocalDate requested) {
    LocalDate pmsDate = opera.businessDate(settings.hotelId());
    if (requested != null && !requested.equals(pmsDate)) {
      throw new InvalidRequestException("Business date must be the hotel's current business date, " + pmsDate);
    }
    return pmsDate;
  }
}
