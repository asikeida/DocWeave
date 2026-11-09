package me.asikeida.docweave.organization.service;

import java.time.Clock;
import me.asikeida.docweave.auth.service.AuthFailure;
import me.asikeida.docweave.organization.dto.PersonalArea;
import me.asikeida.docweave.organization.repository.PersonalAreaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonalAreaService {
    private final PersonalAreaRepository areas;
    private final Clock clock;
    public PersonalAreaService(PersonalAreaRepository areas, Clock clock) { this.areas = areas; this.clock = clock; }

    /** 同一用户行锁串行化补建；已有但停用的区域不能通过初始化重新激活。 */
    @Transactional
    public PersonalArea initialize(String userId) {
        if (!areas.lockActiveUser(userId)) { throw AuthFailure.invalidLogin(); }
        if (!areas.existsFor(userId)) { areas.create(userId, clock.instant()); }
        return current(userId);
    }

    @Transactional(readOnly = true)
    public PersonalArea current(String userId) {
        return areas.findAuthorized(userId).orElseThrow(() ->
                new AuthFailure("PERSONAL_AREA_UNAVAILABLE", "个人区域尚未初始化或暂不可用", 404));
    }
}
