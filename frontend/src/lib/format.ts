/** Club time zone (spec: Europe/Zagreb). All instants from the API are UTC and are displayed in this zone. */
export const CLUB_TIME_ZONE = 'Europe/Zagreb';

const dateTimeFormat = new Intl.DateTimeFormat('hr-HR', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: CLUB_TIME_ZONE,
});

export const formatDateTime = (iso: string) => dateTimeFormat.format(new Date(iso));
