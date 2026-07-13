package it.unimib.datai.nanofaas.cli.commands;

import picocli.CommandLine.IVersionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class VersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = VersionProvider.class.getResourceAsStream("/nanofaas-cli.properties")) {
            if (input == null) {
                return new String[]{"nanofaas development"};
            }
            properties.load(input);
        }
        return new String[]{"nanofaas " + properties.getProperty("version", "development")};
    }
}
