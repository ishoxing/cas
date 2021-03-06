package org.apereo.cas.adaptors.duo.authn;

import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.HandlerResult;
import org.apereo.cas.authentication.PreventedException;
import org.apereo.cas.authentication.handler.support.AbstractPreAndPostProcessingAuthenticationHandler;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.services.MultifactorAuthenticationProvider;
import org.apereo.cas.services.VariegatedMultifactorAuthenticationProvider;
import org.apereo.cas.web.support.WebUtils;
import org.springframework.webflow.execution.RequestContext;
import org.springframework.webflow.execution.RequestContextHolder;

import javax.security.auth.login.FailedLoginException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Authenticate CAS credentials against Duo Security.
 *
 * @author Misagh Moayyed
 * @author Dmitriy Kopylenko
 * @since 4.2
 */
public class DuoAuthenticationHandler extends AbstractPreAndPostProcessingAuthenticationHandler {

    private VariegatedMultifactorAuthenticationProvider provider;

    public DuoAuthenticationHandler() {
    }

    /**
     * Do an out of band request using the DuoWeb api (encapsulated in DuoAuthenticationService)
     * to the hosted duo service. If it is successful
     * it will return a String containing the username of the successfully authenticated user, but if not - will
     * return a blank String or null.
     *
     * @param credential Credential to authenticate.
     * @return the result of this handler
     * @throws GeneralSecurityException general security exception for errors
     * @throws PreventedException       authentication failed exception
     */
    @Override
    protected HandlerResult doAuthentication(final Credential credential) throws GeneralSecurityException, PreventedException {
        if (credential instanceof DuoDirectCredential) {
            logger.debug("Attempting to directly authenticate credential against Duo");
            return authenticateDuoApiCredential(credential);
        }
        return authenticateDuoCredential(credential);
    }

    private HandlerResult authenticateDuoApiCredential(final Credential credential) throws FailedLoginException {
        try {
            final DuoAuthenticationService duoAuthenticationService = getDuoAuthenticationService();
            final DuoDirectCredential c = DuoDirectCredential.class.cast(credential);
            if (duoAuthenticationService.authenticate(c).getKey()) {
                final Principal principal = c.getAuthentication().getPrincipal();
                logger.debug("Duo has successfully authenticated {}", principal.getId());
                return createHandlerResult(credential, principal, new ArrayList<>());
            }
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        }
        throw new FailedLoginException("Duo authentication has failed");
    }

    private HandlerResult authenticateDuoCredential(final Credential credential) throws FailedLoginException {
        try {
            final DuoCredential duoCredential = (DuoCredential) credential;
            if (!duoCredential.isValid()) {
                throw new GeneralSecurityException("Duo credential validation failed. Ensure a username "
                        + " and the signed Duo response is configured and passed. Credential received: " + duoCredential);
            }

            final DuoAuthenticationService duoAuthenticationService = getDuoAuthenticationService();
            final String duoVerifyResponse = duoAuthenticationService.authenticate(duoCredential).getValue();
            logger.debug("Response from Duo verify: [{}]", duoVerifyResponse);
            final String primaryCredentialsUsername = duoCredential.getUsername();

            final boolean isGoodAuthentication = duoVerifyResponse.equals(primaryCredentialsUsername);

            if (isGoodAuthentication) {
                logger.info("Successful Duo authentication for [{}]", primaryCredentialsUsername);

                final Principal principal = this.principalFactory.createPrincipal(duoVerifyResponse);
                return createHandlerResult(credential, principal, new ArrayList<>());
            }
            throw new FailedLoginException("Duo authentication username "
                    + primaryCredentialsUsername + " does not match Duo response: " + duoVerifyResponse);

        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
            throw new FailedLoginException(e.getMessage());
        }
    }

    private DuoAuthenticationService getDuoAuthenticationService() {
        final RequestContext requestContext = RequestContextHolder.getRequestContext();
        if (requestContext == null) {
            throw new IllegalArgumentException("No request context is held to locate the Duo authentication service");
        }
        final Collection<MultifactorAuthenticationProvider> col = WebUtils.getResolvedMultifactorAuthenticationProviders(requestContext);
        if (col.isEmpty()) {
            throw new IllegalArgumentException("No multifactor providers are found in the current request context");
        }
        final MultifactorAuthenticationProvider pr = col.iterator().next();
        return provider.findProvider(pr.getId(), DuoMultifactorAuthenticationProvider.class).getDuoAuthenticationService();
    }

    @Override
    public boolean supports(final Credential credential) {
        return DuoCredential.class.isAssignableFrom(credential.getClass())
                || credential instanceof DuoDirectCredential;
    }

    public void setProvider(final VariegatedMultifactorAuthenticationProvider provider) {
        this.provider = provider;
    }
}
