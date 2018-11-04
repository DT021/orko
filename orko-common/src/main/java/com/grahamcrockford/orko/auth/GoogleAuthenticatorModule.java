package com.grahamcrockford.orko.auth;

import com.google.inject.AbstractModule;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorConfig;

class GoogleAuthenticatorModule extends AbstractModule {
  @Override
  protected void configure() {
    bind(GoogleAuthenticator.class).toInstance(
        new GoogleAuthenticator(
          new GoogleAuthenticatorConfig.GoogleAuthenticatorConfigBuilder().build()
        )
      );
  }
}