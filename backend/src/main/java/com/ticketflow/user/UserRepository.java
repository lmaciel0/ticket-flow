package com.ticketflow.user;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.common.ApiException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findByActiveTrueAndRoleInOrderByNameAsc(Collection<Role> roles);

    /**
     * Loads the caller. The token may outlive its user (the demo reset deletes users),
     * so a missing user is a 401, not a 500.
     */
    default User getCurrent(AuthUser authUser) {
        return findById(authUser.id())
                .orElseThrow(() -> ApiException.unauthorized("Sessão inválida. Entre novamente."));
    }
}
