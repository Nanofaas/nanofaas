package it.unimib.datai.nanofaas.forecastingapi;

@FunctionalInterface
public interface ForecastSource { ForecastSnapshot forecast(ForecastQuery query); }
