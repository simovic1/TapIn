package com.tapin.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "users")
class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    private String phone;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false)
    private AccountStatus accountStatus;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected UserAccount() {
    }

    static UserAccount active(String email, String firstName, String lastName, String passwordHash) {
        UserAccount user = new UserAccount();
        user.email = email;
        user.firstName = firstName;
        user.lastName = lastName;
        user.passwordHash = passwordHash;
        user.accountStatus = AccountStatus.ACTIVE;
        return user;
    }

    /** Account created by an invitation: no password until the invitation is accepted. */
    static UserAccount invited(String email, String firstName, String lastName) {
        UserAccount user = new UserAccount();
        user.email = email;
        user.firstName = firstName;
        user.lastName = lastName;
        user.accountStatus = AccountStatus.INVITED;
        return user;
    }

    /** Accepting the invitation: the user confirms their name, optionally adds a phone, and sets a password. */
    void activate(String passwordHash, String firstName, String lastName, String phone) {
        if (accountStatus != AccountStatus.INVITED) {
            throw new IllegalStateException("Only INVITED accounts can be activated");
        }
        this.passwordHash = passwordHash;
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.accountStatus = AccountStatus.ACTIVE;
    }

    Long getId() { return id; }
    String getEmail() { return email; }
    String getFirstName() { return firstName; }
    String getLastName() { return lastName; }
    String getPasswordHash() { return passwordHash; }
    AccountStatus getAccountStatus() { return accountStatus; }
    String getPhone() { return phone; }
    long getVersion() { return version; }
}
